#include <algorithm>
#include <cassert>
#include <chrono>
#include <cstring>
#include <jni.h>
#include <memory>
#include <string>


#include "android/log.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "whisper.h"

#define  LOG_TAG "[me.aap.fermata.Whisper]"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#ifndef NDEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) \
    do {           \
      /* no debug logging */ \
    } while (0)
#endif

static constexpr unsigned OVERLAP_SAMPLES = WHISPER_SAMPLE_RATE * 200 / 1000;
static constexpr unsigned SAMPLES_CAPACITY = WHISPER_SAMPLE_RATE * 180;

static int suitableGpu() {
#ifdef FERMATA_WHISPER_HAS_VULKAN
	try {
		int gpuIndex = 0;
		for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
			auto device = ggml_backend_dev_get(i);
			auto type = ggml_backend_dev_type(device);
			if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU)
				continue;
			auto backend = ggml_backend_dev_backend_reg(device);
			if (backend && std::strcmp(ggml_backend_reg_name(backend), "Vulkan") == 0)
				return gpuIndex;
			++gpuIndex;
		}
	} catch (const std::exception &err) {
		LOGI("GPU discovery failed: %s", err.what());
	}
#endif
	return -1;
}

static whisper_context *openModel(const char *path, int gpuIndex) {
	auto params = whisper_context_default_params();
	params.use_gpu = gpuIndex >= 0;
	params.gpu_device = std::max(0, gpuIndex);
	params.flash_attn = false;
	try {
		return whisper_init_from_file_with_params(path, params);
	} catch (const std::exception &err) {
		LOGI("Whisper initialization failed: %s", err.what());
		return nullptr;
	}
}

struct WhisperSession {
	WhisperSession(whisper_context *ctx, std::string modelPath, std::string vadPath,
			std::string lang, bool gpuRequested, int probeGpu, int threads)
			: ctx(ctx), modelPath(std::move(modelPath)), vadPath(std::move(vadPath)),
				lang(std::move(lang)), gpuRequested(gpuRequested), probeGpu(probeGpu),
				params(whisper_full_default_params(WHISPER_SAMPLING_GREEDY)) {
		reset();
		params.n_threads = std::clamp(threads, 1, 4);
		params.print_special = false;
		params.print_progress = false;
		params.print_realtime = false;
		params.print_timestamps = false;
		params.max_len = 80;
		params.split_on_word = true;

		if (!this->vadPath.empty()) {
			params.vad = true;
			params.vad_model_path = this->vadPath.c_str();
			params.vad_params = whisper_vad_default_params();
		}
	}

	void reset() {
		size = 0;
		consumed = 0;
		timestampOffset = 0;
		curSkip = 0;
		overlapSize = 0;
		reportOverlapMs = 0;
		resamplePhase = 0;
		averageSum = 0;
		averageCount = 0;
		if (ctx) whisper_reset_timings(ctx);
		params.language = ctx && !whisper_is_multilingual(ctx) ? "en" :
				(lang.empty() ? "auto" : lang.c_str());
		params.detect_language = false;
	}

	whisper_context *ctx;
	std::string modelPath;
	std::string vadPath;
	std::string lang;
	bool gpuRequested;
	int probeGpu;
	whisper_full_params params;
	size_t consumed{0};
	size_t timestampOffset{0};
	unsigned size{0};
	int curSkip{0};
	unsigned overlapSize{0};
	int64_t reportOverlapMs{0};
	unsigned resamplePhase{0};
	float averageSum{0};
	unsigned averageCount{0};
	float samples[SAMPLES_CAPACITY]{};
};

template<unsigned S>
using SampleType = std::conditional_t<
		S == 1,
		uint8_t,
		std::conditional_t<S == 3, uint8_t, std::conditional_t<S == 2, int16_t, int32_t>>
>;

template<unsigned S, typename T = SampleType<S>>
struct SampleBuffer {
	SampleBuffer(JNIEnv *env, jobject byteBuf) :
			data(static_cast<T *>(env->GetDirectBufferAddress(byteBuf))),
			size(static_cast<size_t>(env->GetDirectBufferCapacity(byteBuf) / S)) {
		static_assert(S >= 1 && S <= 4, "Unsupported sample size");
	}

	T *data;
	size_t size;

	// Convert a single sample at idx to a whisper sample - float in range [-1.0, 1.0)
	float operator[](size_t idx) {
		constexpr auto div = static_cast<float>(1ULL << (8 * S - 1));
		if constexpr (S == 1) {
			return (static_cast<int>(data[idx]) - 128) / 128.0f;
		} else if constexpr (S == 3) {
			const uint8_t *p = data + idx * 3;
			int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
			// Extend the sign bit from 24th to 32nd bit
			constexpr int32_t sign_bit = 1 << 23;
			return static_cast<float>((v ^ sign_bit) - sign_bit) / div;
		} else {
			return static_cast<float>(data[idx]) / div;
		}
	}

};

template<unsigned S>
static size_t resampleFrames(JNIEnv *env, jobject source, WhisperSession *session,
		unsigned channels, unsigned rate, unsigned target) {
	SampleBuffer<S> input(env, source);
	auto frames = input.size / channels;
	size_t i = 0;
	while (i < frames && session->size < target) {
		float mono = 0;
		for (unsigned channel = 0; channel < channels; ++channel)
			mono += input[i * channels + channel];
		mono /= static_cast<float>(channels);
		++i;

		session->averageSum += mono;
		++session->averageCount;
		session->resamplePhase += WHISPER_SAMPLE_RATE;
		if (session->resamplePhase < rate) continue;

		float sample = session->averageSum / static_cast<float>(session->averageCount);
		do {
			session->samples[session->size++] = sample;
			session->resamplePhase -= rate;
		} while (session->resamplePhase >= rate && session->size < SAMPLES_CAPACITY);
		session->averageSum = 0;
		session->averageCount = 0;
	}
	return i * channels * S;
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_aap_fermata_whisper_Whisper_create(JNIEnv *env, jclass, jstring jModelPath,
																					 jstring jvadPath, jstring jlang,
																					 jboolean useGpu, jint threads) {
	try {
		std::string modelPath;
		std::string lang;
		std::string vadPath;
		for (auto &[value, input] : {std::make_pair(&modelPath, jModelPath),
				std::make_pair(&lang, jlang), std::make_pair(&vadPath, jvadPath)}) {
			if (!input) continue;
			auto release = [&](const char *chars) { env->ReleaseStringUTFChars(input, chars); };
			std::unique_ptr<const char, decltype(release)> chars(env->GetStringUTFChars(input, nullptr), release);
			if (!chars) return 0;
			*value = chars.get();
		}
		int gpu = useGpu ? suitableGpu() : -1;
		std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(
				openModel(modelPath.c_str(), -1), whisper_free);
		bool usingGpu = !ctx && gpu >= 0;
		if (usingGpu) ctx.reset(openModel(modelPath.c_str(), gpu));
		if (!ctx) {
			env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Failed to load Whisper model");
			return 0;
		}
		auto session = new WhisperSession(ctx.get(), std::move(modelPath), std::move(vadPath),
				std::move(lang), usingGpu, usingGpu ? -1 : gpu, threads);
		ctx.release();
		LOGI("Whisper: %s, %d CPU threads", usingGpu ? "Vulkan" : (gpu >= 0 ? "CPU, Vulkan eligible" : "CPU"),
				session->params.n_threads);
		return reinterpret_cast<jlong>(session);
	} catch (const std::bad_alloc &) {
		env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Failed to allocate Whisper session");
		return 0;
	}
}

extern "C" JNIEXPORT void JNICALL
Java_me_aap_fermata_whisper_Whisper_reconfigure(JNIEnv *env, jclass, jlong sessionPtr,
																								jstring jlang) {
	assert(sessionPtr);
	auto session = reinterpret_cast<WhisperSession *>(sessionPtr);
	auto chars = env->GetStringUTFChars(jlang, nullptr);
	session->lang = chars;
	env->ReleaseStringUTFChars(jlang, chars);
	session->reset();
}

extern "C" JNIEXPORT jint JNICALL
Java_me_aap_fermata_whisper_Whisper_resample(JNIEnv *env, jclass, jlong sessionPtr, jobject byteBuf,
																						 jint chunkLen, jint bytesPerSample, jint channels,
																						 jint frameRate) {
	assert(sessionPtr);
	if (channels <= 0 || frameRate < 1000 || chunkLen <= 0 ||
			env->GetDirectBufferCapacity(byteBuf) < 0) {
		env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid PCM audio format");
		return 0;
	}

	auto session = reinterpret_cast<WhisperSession *>(sessionPtr);
	auto target = static_cast<unsigned>(std::min(chunkLen, 60)) * WHISPER_SAMPLE_RATE;
	size_t consumed;
	switch (bytesPerSample) {
		case 1: consumed = resampleFrames<1>(env, byteBuf, session, channels, frameRate, target); break;
		case 2: consumed = resampleFrames<2>(env, byteBuf, session, channels, frameRate, target); break;
		case 3: consumed = resampleFrames<3>(env, byteBuf, session, channels, frameRate, target); break;
		case 4: consumed = resampleFrames<4>(env, byteBuf, session, channels, frameRate, target); break;
		default:
			env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Unsupported PCM sample size");
			return 0;
	}
	auto result = static_cast<jint>(consumed);
	return session->size >= target ? -result : result;
}

static int runTranscription(WhisperSession *session) {
	if (!session->ctx) return -1;
	try {
		return whisper_full(session->ctx, session->params, session->samples,
				static_cast<int>(session->size));
	} catch (const std::exception &err) {
		LOGI("Whisper transcription failed: %s", err.what());
		return -1;
	}
}

static int selectBackend(WhisperSession *session) {
	using Clock = std::chrono::steady_clock;
	auto start = Clock::now();
	int result = runTranscription(session);
	auto cpuTime = Clock::now() - start;
	if (result == 0 && session->probeGpu >= 0 && whisper_full_n_segments(session->ctx) > 0) {
		int gpu = session->probeGpu;
		session->probeGpu = -1;
		// Keep one copy of the model in memory. Both backends see the same audio.
		whisper_free(session->ctx);
		session->ctx = openModel(session->modelPath.c_str(), gpu);
		start = Clock::now();
		int gpuResult = runTranscription(session);
		auto gpuTime = Clock::now() - start;
		if (gpuResult == 0 && whisper_full_n_segments(session->ctx) > 0 && gpuTime < cpuTime) {
			session->gpuRequested = true;
			LOGI("Whisper benchmark selected Vulkan");
			return 0;
		}
		if (session->ctx) whisper_free(session->ctx);
		session->ctx = openModel(session->modelPath.c_str(), -1);
		LOGI("Whisper benchmark selected CPU");
		return runTranscription(session);
	}
	return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_aap_fermata_whisper_Whisper_fullTranscribe(JNIEnv *env, jclass, jlong sessionPtr) {
	assert(sessionPtr);
	auto session = reinterpret_cast<WhisperSession *>(sessionPtr);
	if (session->size <= session->overlapSize) return 0;

	whisper_full_params &params = session->params;
	int result = selectBackend(session);
	if (result != 0 && session->gpuRequested) {
		LOGI("Vulkan inference failed, retrying the current audio with CPU");
		session->gpuRequested = false;
		whisper_free(session->ctx);
		session->ctx = openModel(session->modelPath.c_str(), -1);
		result = runTranscription(session);
	}
	if (result != 0) {
		env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Failed to transcribe");
		return 0;
	}
	// Keep a short overlap for the next chunk, and use the absolute PCM offset for timestamps.
	auto segments = whisper_full_n_segments(session->ctx);
	auto overlapCs = static_cast<int64_t>(session->overlapSize) * 100 / WHISPER_SAMPLE_RATE;
	session->curSkip = 0;
	session->reportOverlapMs = overlapCs * 10;
	while (session->curSkip < segments &&
			whisper_full_get_segment_t1(session->ctx, session->curSkip) <= overlapCs)
		++session->curSkip;
	// Auto detection inside whisper_full shares its feature extraction with decoding.
	// Ignore empty output so opening silence does not pin the session language.
	if (std::strcmp(params.language, "auto") == 0 && segments > session->curSkip) {
		for (int i = session->curSkip; i < segments; ++i) {
			auto text = whisper_full_get_segment_text(session->ctx, i);
			if (text == nullptr || text[std::strspn(text, " \t\r\n")] == '\0') continue;
			auto detected = whisper_lang_str(whisper_full_lang_id(session->ctx));
			if (detected) params.language = detected;
			break;
		}
	}
	session->timestampOffset = session->consumed * 1000 / WHISPER_SAMPLE_RATE;
	auto overlap = session->size > OVERLAP_SAMPLES ? OVERLAP_SAMPLES : 0u;
	auto shift = session->size - overlap;
	std::memmove(session->samples, session->samples + shift, overlap * sizeof(float));
	session->size = overlap;
	session->overlapSize = overlap;
	session->consumed += shift;
	return segments - session->curSkip;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_aap_fermata_whisper_Whisper_text(JNIEnv *env, jclass, jlong sessionPtr, jint segmentIdx) {
	assert(sessionPtr);
	auto session = reinterpret_cast<WhisperSession *>(sessionPtr);
	const char *text = whisper_full_get_segment_text(session->ctx, segmentIdx + session->curSkip);
	auto size = text ? static_cast<jsize>(std::strlen(text)) : 0;
	auto bytes = env->NewByteArray(size);
	if (bytes && size) env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte *>(text));
	return bytes;
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_aap_fermata_whisper_Whisper_start(JNIEnv *, jclass, jlong sessionPtr, jint segmentIdx) {
	assert(sessionPtr);
	auto session = reinterpret_cast<WhisperSession *>(sessionPtr);
	auto t = session->timestampOffset +
			std::max(session->reportOverlapMs,
					whisper_full_get_segment_t0(session->ctx, segmentIdx + session->curSkip) * 10);
	return static_cast<jlong>(t);
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_aap_fermata_whisper_Whisper_end(JNIEnv *, jclass, jlong sessionPtr, jint segmentIdx) {
	assert(sessionPtr);
	auto session = reinterpret_cast<WhisperSession *>(sessionPtr);
	auto t = session->timestampOffset +
					 whisper_full_get_segment_t1(session->ctx, segmentIdx + session->curSkip) * 10;
	return static_cast<jlong>(t);
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_aap_fermata_whisper_Whisper_lang(JNIEnv *env, jclass, jlong sessionPtr) {
	assert(sessionPtr);
	auto session = reinterpret_cast<WhisperSession *>(sessionPtr);
	auto lang = session->params.language;
	if (lang == nullptr || std::strcmp("auto", lang) == 0) {
		lang = session->ctx ? whisper_lang_str(whisper_full_lang_id(session->ctx)) : nullptr;
	}
	return env->NewStringUTF(lang == nullptr ? "" : lang);
}

extern "C" JNIEXPORT void JNICALL
Java_me_aap_fermata_whisper_Whisper_reset(JNIEnv *, jclass, jlong sessionPtr) {
	assert(sessionPtr);
	auto session = reinterpret_cast<WhisperSession *>(sessionPtr);
	session->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_me_aap_fermata_whisper_Whisper_release(JNIEnv *, jclass, jlong sessionPtr) {
	if (!sessionPtr) return;
	auto session = reinterpret_cast<WhisperSession *>(sessionPtr);
	if (session->ctx) whisper_free(session->ctx);
	delete session;
}