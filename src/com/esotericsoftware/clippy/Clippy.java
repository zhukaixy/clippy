/* Copyright (c) 2014, Esoteric Software
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.esotericsoftware.clippy;

import static com.esotericsoftware.clippy.Win.User32.*;
import static com.esotericsoftware.minlog.Log.*;

import java.awt.EventQueue;
import java.awt.Font;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.sql.SQLException;

import javax.swing.KeyStroke;
import javax.swing.UIManager;

import com.esotericsoftware.clippy.ClipDataStore.ClipConnection;
import com.esotericsoftware.clippy.Win.POINT;
import com.esotericsoftware.clippy.util.MultiplexOutputStream;
import com.esotericsoftware.clippy.util.TextItem;
import com.esotericsoftware.minlog.Log;
import com.sun.jna.WString;

// BOZO - Favorites that always show up before others when searching.

/** @author Nathan Sweet */
public class Clippy {
	static public Clippy instance;
	static public final File logFile = new File(System.getProperty("user.home"), ".clippy/clippy.log");

	Config config;
	ClipDataStore db;
	Popup popup;
	Menu menu;
	Tray tray;
	Keyboard keyboard;
	Clipboard clipboard;
	Screenshot screenshot;
	Upload textUpload, imageUpload, fileUpload;

	public Clippy () {
		instance = this;

		if (Log.ERROR) {
			try {
				FileOutputStream output = new FileOutputStream(logFile);
				System.setOut(new PrintStream(new MultiplexOutputStream(System.out, output), true));
				System.setErr(new PrintStream(new MultiplexOutputStream(System.err, output), true));
			} catch (Throwable ex) {
				if (WARN) warn("Unable to write log file.", ex);
			}
		}

		Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
			public void uncaughtException (Thread thread, Throwable ex) {
				if (ERROR) error("Uncaught exception, exiting.", ex);
				System.exit(0);
			}
		});

		config = new Config();

		if (config.imageUpload != null) {
			switch (config.imageUpload) {
			case sftp:
				imageUpload = new Upload.Sftp();
				break;
			case ftp:
				imageUpload = new Upload.Ftp();
				break;
			case imgur:
				imageUpload = new Upload.Imgur();
				break;
			}
		}
		if (config.textUpload != null) {
			switch (config.textUpload) {
			case sftp:
				textUpload = new Upload.Sftp();
				break;
			case ftp:
				textUpload = new Upload.Ftp();
				break;
			case pastebin:
				textUpload = new Upload.Pastebin();
				break;
			}
		}
		if (config.fileUpload != null) {
			switch (config.fileUpload) {
			case sftp:
				fileUpload = new Upload.Sftp();
				break;
			case ftp:
				fileUpload = new Upload.Ftp();
				break;
			}
		}

		TextItem.font = Font.decode(config.font);

		try {
			db = new ClipDataStore();
		} catch (SQLException ex) {
			if (ERROR) error("Error opening clip database.", ex);
			System.exit(0);
		}

		screenshot = new Screenshot();

		final KeyStroke popupHotkey = KeyStroke.getKeyStroke(config.popupHotkey);
		final KeyStroke uploadHotkey = KeyStroke.getKeyStroke(config.uploadHotkey);
		final KeyStroke imgurScreenshotHotkey = KeyStroke.getKeyStroke(config.screenshotHotkey);
		final KeyStroke imgurScreenshotAppHotkey = KeyStroke.getKeyStroke(config.screenshotAppHotkey);
		final KeyStroke imgurScreenshotRegionHotkey = KeyStroke.getKeyStroke(config.screenshotRegionHotkey);
		final KeyStroke imgurScreenshotLastRegionHotkey = KeyStroke.getKeyStroke(config.screenshotLastRegionHotkey);
		keyboard = new Keyboard() {
			protected void hotkey (KeyStroke keyStroke) {
				if (keyStroke.equals(popupHotkey))
					showPopup(keyStroke);
				else if (keyStroke.equals(uploadHotkey)) //
					upload();
				else if (keyStroke.equals(imgurScreenshotHotkey)) //
					screenshot.screen();
				else if (keyStroke.equals(imgurScreenshotAppHotkey)) //
					screenshot.app();
				else if (keyStroke.equals(imgurScreenshotRegionHotkey)) //
					screenshot.region();
				else if (keyStroke.equals(imgurScreenshotLastRegionHotkey)) //
					screenshot.lastRegion();
			}
		};
		if (popupHotkey != null) keyboard.registerHotkey(popupHotkey);
		if (uploadHotkey != null) keyboard.registerHotkey(uploadHotkey);
		if (imgurScreenshotHotkey != null) keyboard.registerHotkey(imgurScreenshotHotkey);
		if (imgurScreenshotAppHotkey != null) keyboard.registerHotkey(imgurScreenshotAppHotkey);
		if (imgurScreenshotRegionHotkey != null) keyboard.registerHotkey(imgurScreenshotRegionHotkey);
		if (imgurScreenshotLastRegionHotkey != null) keyboard.registerHotkey(imgurScreenshotLastRegionHotkey);
		keyboard.start();

		clipboard = new Clipboard(config.maxLengthToStore) {
			protected void changed () {
				String text = clipboard.getContents();
				if (text != null) store(text);
			}
		};

		popup = new Popup();

		menu = new Menu();

		tray = new Tray() {
			protected void mouseDown (POINT position, int button) {
				menu.setLocation(position.x, position.y - menu.getHeight());
				menu.showPopup();
			}
		};

		String text = clipboard.getContents();
		if (text != null) store(text);

		new BreakWarning();
		new Gamma();
		new PhilipsHue();

		if (INFO) info("Started.");
	}

	void upload () {
		String text = clipboard.getContents();
		switch (clipboard.getDataType()) {
		case text:
			Upload.uploadText(text);
			break;
		case files:
			String[] files = text.split("\n");
			if (files.length > 0) Upload.uploadFiles(files);
			break;
		}
	}

	void showPopup (KeyStroke keyStroke) {
		popup.showPopup();
	}

	void store (String text) {
		if (text.length() > config.maxLengthToStore) {
			if (TRACE) trace("Text too large to store: " + text.length());
			return;
		}
		if (TRACE) trace("Store clipboard text: " + text.trim());
		try {
			ClipConnection conn = db.getThreadConnection();
			if (!config.allowDuplicateClips) conn.removeText(text);
			int id = conn.add(text);
			popup.addRecentItem(id, text);
		} catch (SQLException ex) {
			if (ERROR) error("Error storing clipboard text.", ex);
		}
	}

	/** @param text May be null.
	 * @return The new ID for the clipboard item that was moved to last, or -1. */
	public int paste (String text) {
		int newID = -1;
		if (text == null) return newID;
		if (!clipboard.setContents(text)) return newID;

		try {
			if (!popup.lockCheckbox.isSelected()) {
				newID = db.getThreadConnection().makeLast(text);
				popup.makeLast(newID, text);
			}
		} catch (SQLException ex) {
			if (ERROR) error("Error moving clipboard text to last.", ex);
		}

		// Could use SendInput or menu->Edit->Paste, or users could install the clink CMD prompt addon or use Windows 10.
		// char[] chars = new char[2048];
		// int count = GetClassName(GetForegroundWindow(), chars, chars.length);
		// if (count > 0) {
		// if (new String(chars, 0, count).equals("ConsoleWindowClass")) {
		// }
		// }

		// Reset modifier key state in case they were down.
		keyboard.sendKeyUp(VK_MENU);
		keyboard.sendKeyUp(VK_SHIFT);
		keyboard.sendKeyUp(VK_CONTROL);

		keyboard.sendKeyDown(VK_CONTROL);
		keyboard.sendKeyDown((byte)'V');
		keyboard.sendKeyUp((byte)'V');
		keyboard.sendKeyUp(VK_CONTROL);
		return newID;
	}

	public static void main (String[] args) throws Exception {
		if (FindWindow(new WString("STATIC"), new WString("com.esotericsoftware.clippy")) != null) {
			if (ERROR) error("Already running.");
			return;
		}

		try {
			UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsClassicLookAndFeel");
		} catch (Throwable ignored) {
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			} catch (Throwable ignored2) {
			}
		}

		EventQueue.invokeLater(new Runnable() {
			public void run () {
				new Clippy();
			}
		});
	}
}
