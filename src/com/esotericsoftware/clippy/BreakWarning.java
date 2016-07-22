
package com.esotericsoftware.clippy;

import java.awt.Color;
import java.awt.EventQueue;
import java.util.Timer;
import java.util.TimerTask;

import com.esotericsoftware.clippy.Win.LASTINPUTINFO;
import com.esotericsoftware.clippy.util.Util;

public class BreakWarning {
	final Clippy clippy = Clippy.instance;
	final LASTINPUTINFO lastInputInfo = new LASTINPUTINFO();
	long lastBreakTime = System.currentTimeMillis();
	volatile ProgressBar progressBar;

	public BreakWarning () {
		if (clippy.config.breakWarningMinutes <= 0) return;

		new Timer("BreakWarning Timer", true).schedule(new TimerTask() {
			public void run () {
				if (progressBar != null) return;

				long inactiveMinutes = getInactiveMillis() / 1000 / 60;
				if (inactiveMinutes >= clippy.config.breakResetMinutes) lastBreakTime = System.currentTimeMillis();

				long activeMinutes = (System.currentTimeMillis() - lastBreakTime) / 1000 / 60;
				if (activeMinutes >= clippy.config.breakWarningMinutes) showBreakDialog();
			}
		}, clippy.config.breakWarningMinutes * 60 * 1000, 60 * 1000);
	}

	void showBreakDialog () {
		EventQueue.invokeLater(new Runnable() {
			public void run () {
				progressBar = new ProgressBar("");
				progressBar.clickToDispose = false;
				progressBar.progressBar.setForeground(new Color(0xff341c));
				new Thread("BreakWarning Dialog") {
					{
						setDaemon(true);
					}

					public void run () {
						while (true) {
							long inactiveMillis = getInactiveMillis();
							long inactiveMinutes = inactiveMillis / 1000 / 60;
							if (inactiveMinutes >= clippy.config.breakResetMinutes) break;

							long activeMinutes = (System.currentTimeMillis() - lastBreakTime) / 1000 / 60;
							long hours = activeMinutes / 60, minutes = activeMinutes - hours * 60;
							String minutesMessage = minutes + " minute" + (minutes == 1 ? "" : "s");
							String hoursMessage = hours + " hour" + (hours == 1 ? "" : "s");
							String message;
							if (hours == 0)
								message = "Active: " + minutesMessage;
							else
								message = "Active: " + hoursMessage + ", " + minutesMessage;
							progressBar.progressBar.setString(message);

							progressBar.setProgress(1 - inactiveMillis / (float)(clippy.config.breakResetMinutes * 60 * 1000));
							Util.sleep(16);
						}
						lastBreakTime = System.currentTimeMillis();
						progressBar.done("Break complete!");
						progressBar = null;
					}
				}.start();
			}
		});
	}

	long getInactiveMillis () {
		Win.User32.GetLastInputInfo(lastInputInfo);
		return Win.Kernel32.GetTickCount() - lastInputInfo.dwTime;
	}
}