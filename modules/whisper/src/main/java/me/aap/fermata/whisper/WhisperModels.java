package me.aap.fermata.whisper;

import java.util.List;

/** Model sizes and SHA-256 digests from the publishers' Hugging Face LFS metadata. */
final class WhisperModels {
	static final List<Model> MODELS = List.of(
			new Model("tiny-q5_1", "ggerganov", 32152673L,
					"818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
			new Model("tiny-q8_0", "ggerganov", 43537433L,
					"c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca"),
			new Model("tiny", "ggerganov", 77691713L,
					"be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"),
			new Model("tiny.en-q5_1", "ggerganov", 32166155L,
					"c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b"),
			new Model("tiny.en-q8_0", "ggerganov", 43550795L,
					"5bc2b3860aa151a4c6e7bb095e1fcce7cf12c7b020ca08dcec0c6d018bb7dd94"),
			new Model("tiny.en", "ggerganov", 77704715L,
					"921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f"),
			new Model("base-q4_k", "AndreyPavlenko", 46471049L,
					"9fd03d225ccbd11803addde7178063b57c6fd90a7f8b5b542ed5e12b40667cfc"),
			new Model("base-q5_k", "AndreyPavlenko", 55295433L,
					"2db285f08d61a274b8cdce946b43e921be8180fad96ee40aa43edcc2ccc05d2b"),
			new Model("base-q5_1", "ggerganov", 59707625L,
					"422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
			new Model("base-q6_k", "AndreyPavlenko", 64671341L,
					"2b2ea725733a619dc390d28b71f602dabb1ab09b0cdef4e411626fac8bb4e685"),
			new Model("base-q8_0", "ggerganov", 81768585L,
					"c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9"),
			new Model("base", "ggerganov", 147951465L,
					"60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"),
			new Model("base.en-q5_1", "ggerganov", 59721011L,
					"4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"),
			new Model("base.en-q8_0", "ggerganov", 81781811L,
					"a4d4a0768075e13cfd7e19df3ae2dbc4a68d37d36a7dad45e8410c9a34f8c87e"),
			new Model("base.en", "ggerganov", 147964211L,
					"a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002"),
			new Model("small-q4_k", "AndreyPavlenko", 145458015L,
					"72f958dd68ec1b048d10f18ed10a365fc2322d4fd23a98a5bebedb41611d45c0"),
			new Model("small-q5_1", "ggerganov", 190085487L,
					"ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
			new Model("small-q8_0", "ggerganov", 264464607L,
					"49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f"),
			new Model("small", "ggerganov", 487601967L,
					"1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"),
			new Model("small.en-q5_1", "ggerganov", 190098681L,
					"bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"),
			new Model("small.en-q8_0", "ggerganov", 264477561L,
					"67a179f608ea6114bd3fdb9060e762b588a3fb3bd00c4387971be4d177958067"),
			new Model("small.en", "ggerganov", 487614201L,
					"c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d"),
			new Model("medium-q5_0", "ggerganov", 539212467L,
					"19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f"),
			new Model("medium-q8_0", "ggerganov", 823369779L,
					"42a1ffcbe4167d224232443396968db4d02d4e8e87e213d3ee2e03095dea6502"),
			new Model("medium", "ggerganov", 1533763059L,
					"6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208"),
			new Model("medium.en-q5_0", "ggerganov", 539225533L,
					"76733e26ad8fe1c7a5bf7531a9d41917b2adc0f20f2e4f5531688a8c6cd88eb0"),
			new Model("medium.en-q8_0", "ggerganov", 823382461L,
					"43fa2cd084de5a04399a896a9a7a786064e221365c01700cea4666005218f11c"),
			new Model("medium.en", "ggerganov", 1533774781L,
					"cc37e93478338ec7700281a7ac30a10128929eb8f427dda2e865faa8f6da4356"),
			new Model("large-v3-turbo-q3_k", "Pomni/whisper-large-v3-turbo-ggml-allquants",
					"3933d9b9ee129b54cf315d1fb88c937ffb122b77", 367690215L,
					"2f0e22be97e601aa8bd1a732145ab3ebfecc6d14d8a931ce72205506d95c23e5"),
			new Model("nava-q5_0", "Nimruz/Nava-ggml", "6fa32f2d020b0ddf0c599c45751cc025e5a57afb",
					574041195L, "e788c333480c50fbde2e4c7c145a46f90fed6e055892c9d81582a6d11af84b0f"),
			new Model("large-v3-turbo-q4_0", "Pomni/whisper-large-v3-turbo-ggml-allquants",
					"2df9591948d91c0a80d47faa2a3a96d195ce138a", 473992235L,
					"2ceb779ca61ce46ed6e9aa43c4a0f8a89a37f683df3c0ba3119238fcd5852155"),
			new Model("large-v3-turbo-q5_0", "ggerganov", 574041195L,
					"394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
			new Model("large-v3-turbo-q8_0", "ggerganov", 874188075L,
					"317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1"),
			new Model("large-v3-turbo", "ggerganov", 1624555275L,
					"1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69")
	);

	static Model find(String id) {
		for (var model : MODELS) if (model.id().equals(id)) return model;
		throw new IllegalArgumentException("Unknown Whisper model: " + id);
	}

	record Model(String id, String repository, String revision, long bytes, String sha256) {
		Model(String id, String owner, long bytes, String sha256) {
			this(id, owner + "/whisper.cpp", "main", bytes, sha256);
		}

		String filename() {
			return "ggml-" + id + ".bin";
		}

		String url() {
			return "https://huggingface.co/" + repository + "/resolve/" + revision + "/" + filename()
					+ "?download=true";
		}

		boolean englishOnly() {
			return id.contains(".en");
		}

		String fixedLanguage() {
			if (englishOnly()) return "en";
			return id.equals("nava-q5_0") ? "fa" : null;
		}
	}

	private WhisperModels() {}
}
