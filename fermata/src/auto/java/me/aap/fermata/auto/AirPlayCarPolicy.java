package me.aap.fermata.auto;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.car.Car;
import android.support.car.CarConnectionCallback;
import android.support.car.CarNotConnectedException;
import android.support.car.hardware.CarSensorEvent;
import android.support.car.hardware.CarSensorManager;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.aap.fermata.mirror.AirPlayPanel;

import static android.support.car.hardware.CarSensorEvent.DRIVE_STATUS_UNRESTRICTED;
import static android.support.car.hardware.CarSensorEvent.GEAR_PARK;
import static android.support.car.hardware.CarSensorManager.SENSOR_RATE_NORMAL;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_DRIVING_STATUS;
import static android.support.car.hardware.CarSensorManager.SENSOR_TYPE_GEAR;

final class AirPlayCarPolicy implements AutoCloseable {
	private static final String TAG = "AirPlayCarPolicy";
	private static final long WATCHDOG_MS = 1500;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final java.util.concurrent.ScheduledExecutorService polling =
			Executors.newSingleThreadScheduledExecutor();
	private final AirPlayPanel panel;
	private final Runnable watchdog = this::revoke;
	private final CarSensorManager.OnSensorChangedListener changes = this::changed;
	private Car car;
	private CarSensorManager sensors;
	private boolean closed;
	private boolean revoked;
	private boolean playingAllowed;

	AirPlayCarPolicy(Context context, AirPlayPanel panel) {
		this.panel = panel;
		car = Car.createCar(context, new CarConnectionCallback() {
			@Override public void onConnected(Car car) { main.post(() -> connected(car)); }
			@Override public void onDisconnected(Car car) { main.post(AirPlayCarPolicy.this::revoke); }
		});
		if (car != null) car.connect();
	}

	private void connected(Car active) {
		if (closed || revoked || active != car || sensors != null) return;
		try {
			var manager = active.getCarManager(CarSensorManager.class);
			if (manager == null) return;
			sensors = manager;
			if (!manager.addListener(changes, SENSOR_TYPE_DRIVING_STATUS, SENSOR_RATE_NORMAL)
					|| !manager.addListener(changes, SENSOR_TYPE_GEAR, SENSOR_RATE_NORMAL)) {
				revoke();
				return;
			}
			polling.scheduleWithFixedDelay(() -> sample(manager), 0, 500, TimeUnit.MILLISECONDS);
		} catch (CarNotConnectedException | RuntimeException e) {
			Log.w(TAG, "Projected host did not provide car restrictions", e);
			revoke();
		}
	}

	private void changed(CarSensorManager manager, CarSensorEvent event) {
		if (event == null || (event.sensorType == SENSOR_TYPE_GEAR && !parked(event))
				|| (event.sensorType == SENSOR_TYPE_DRIVING_STATUS && !videoAllowed(event))) {
			main.post(() -> {
				if (manager == sensors && playingAllowed) revoke();
			});
		}
	}

	private void sample(CarSensorManager manager) {
		boolean permit = false;
		long started = SystemClock.elapsedRealtime();
		try {
			permit = parked(manager.getLatestSensorEvent(SENSOR_TYPE_GEAR)) &&
					videoAllowed(manager.getLatestSensorEvent(SENSOR_TYPE_DRIVING_STATUS));
		} catch (CarNotConnectedException | RuntimeException e) {
			Log.d(TAG, "Car restriction sample unavailable", e);
		}
		boolean permitted = permit;
		main.post(() -> apply(manager, started, permitted));
	}

	private void apply(CarSensorManager manager, long sampleStarted, boolean permitted) {
		if (closed || revoked || manager != sensors) return;
		long remaining = WATCHDOG_MS - (SystemClock.elapsedRealtime() - sampleStarted);
		if (!permitted || remaining <= 0) {
			if (playingAllowed) revoke();
			return;
		}
		main.removeCallbacks(watchdog);
		main.postDelayed(watchdog, remaining);
		if (!playingAllowed) {
			playingAllowed = true;
			panel.setVideoAllowed(true);
		}
	}

	static boolean parked(CarSensorEvent event) {
		return event != null && event.sensorType == SENSOR_TYPE_GEAR && event.timestamp > 0
				&& event.intValues != null && event.intValues.length > 0
				&& event.getGearData().gear == GEAR_PARK;
	}

	static boolean videoAllowed(CarSensorEvent event) {
		return event != null && event.sensorType == SENSOR_TYPE_DRIVING_STATUS && event.timestamp > 0
				&& event.intValues != null && event.intValues.length > 0
				&& event.getDrivingStatusData().status == DRIVE_STATUS_UNRESTRICTED;
	}

	private void revoke() {
		revoked = true;
		playingAllowed = false;
		main.removeCallbacks(watchdog);
		panel.setVideoAllowed(false);
		polling.shutdownNow();
	}

	@Override
	public void close() {
		closed = true;
		revoke();
		try {
			if (sensors != null) sensors.removeListener(changes);
		} catch (RuntimeException e) {
			Log.d(TAG, "Car restriction listener already unavailable", e);
		}
		try {
			if (car != null) car.disconnect();
		} catch (RuntimeException e) {
			Log.d(TAG, "Car restriction host already disconnected", e);
		}
		sensors = null;
		car = null;
	}
}
