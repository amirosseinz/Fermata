package me.aap.fermata.auto;

import android.support.car.hardware.CarSensorEvent;

import org.junit.Test;
import static android.support.car.hardware.CarSensorEvent.*;
import static android.support.car.hardware.CarSensorManager.*;
import static org.junit.Assert.*;

public class AirPlayCarPolicyTest {
	private static CarSensorEvent event(int type, long timestamp, int value) {
		return new CarSensorEvent(type, timestamp, null, new int[]{value}, null);
	}

	@Test
	public void onlyAcceptsAffirmativeParkingDataFromTheExpectedSensor() {
		assertTrue(AirPlayCarPolicy.parked(event(SENSOR_TYPE_GEAR, 1, GEAR_PARK)));
		assertFalse(AirPlayCarPolicy.parked(event(SENSOR_TYPE_GEAR, 1, GEAR_DRIVE)));
		assertFalse(AirPlayCarPolicy.parked(event(SENSOR_TYPE_GEAR, 0, GEAR_PARK)));
		assertFalse(AirPlayCarPolicy.parked(event(SENSOR_TYPE_DRIVING_STATUS, 1, GEAR_PARK)));
		assertFalse(AirPlayCarPolicy.parked(null));
	}

	@Test
	public void videoRestrictionUnknownAndMissingDataAllCloseTheGate() {
		assertTrue(AirPlayCarPolicy.videoAllowed(event(SENSOR_TYPE_DRIVING_STATUS, 1, DRIVE_STATUS_UNRESTRICTED)));
		assertFalse(AirPlayCarPolicy.videoAllowed(event(SENSOR_TYPE_DRIVING_STATUS, 1, DRIVE_STATUS_NO_VIDEO)));
		assertFalse(AirPlayCarPolicy.videoAllowed(event(SENSOR_TYPE_DRIVING_STATUS, 1, 0x4000)));
		assertFalse(AirPlayCarPolicy.videoAllowed(event(SENSOR_TYPE_DRIVING_STATUS, 0, DRIVE_STATUS_UNRESTRICTED)));
		assertFalse(AirPlayCarPolicy.videoAllowed(new CarSensorEvent(SENSOR_TYPE_DRIVING_STATUS,
				1, null, new int[0], null)));
		assertFalse(AirPlayCarPolicy.videoAllowed(null));
	}
}
