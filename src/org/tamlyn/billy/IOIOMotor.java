package org.tamlyn.billy;

import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.PwmOutput;
import ioio.lib.api.exception.ConnectionLostException;

public class IOIOMotor {
	private PwmOutput pwm;
	private DigitalOutput in1;
	private DigitalOutput in2;
	
	final int pwmFrequency = 100000;
	
	public IOIOMotor(IOIO ioio, int pwmPin, int in1Pin, int in2Pin) throws ConnectionLostException {
		pwm = ioio.openPwmOutput(pwmPin, pwmFrequency);
		pwm.setDutyCycle(0);
		in1 = ioio.openDigitalOutput(in1Pin, false);
		in2 = ioio.openDigitalOutput(in2Pin, false);
	}
	
	public void setSpeed(float speed) throws ConnectionLostException {
		if (speed < 0) {
			in1.write(true);
			in2.write(false);
		} else if (speed > 0) {
			in1.write(false);
			in2.write(true);
		} else {
			in1.write(false);
			in2.write(false);
		}
		pwm.setDutyCycle(Math.abs(speed));
	}
}
