/*
*************************************************************************
vShell - x86 Linux virtual shell application powered by QEMU.
Copyright (C) 2019-2021  Leonid Pliushch <leonid.pliushch@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*************************************************************************
*/
package app.virtshell;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public class TerminalPreferences {

    private static final String PREF_FIRST_RUN = "first_run";
    private static final String PREF_SHOW_EXTRA_KEYS = "show_extra_keys";
    private static final String PREF_IGNORE_BELL = "ignore_bell";
    private static final String PREF_DATA_VERSION = "data_version";

    private boolean mFirstRun;
    private boolean mShowExtraKeys;
    private boolean mIgnoreBellCharacter;
    private int mDataVersion;

    public TerminalPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        mFirstRun = prefs.getBoolean(PREF_FIRST_RUN, true);
        mShowExtraKeys = prefs.getBoolean(PREF_SHOW_EXTRA_KEYS, true);
        mIgnoreBellCharacter = prefs.getBoolean(PREF_IGNORE_BELL, false);
        mDataVersion = prefs.getInt(PREF_DATA_VERSION, 0);
    }

    public boolean isFirstRun() {
        return mFirstRun;
    }

    public void completedFirstRun(Context context) {
        mFirstRun = false;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_FIRST_RUN, mFirstRun).apply();
    }

    public boolean isExtraKeysEnabled() {
        return mShowExtraKeys;
    }

    public boolean toggleShowExtraKeys(Context context) {
        mShowExtraKeys = !mShowExtraKeys;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_SHOW_EXTRA_KEYS, mShowExtraKeys).apply();
        return mShowExtraKeys;
    }

    public boolean isBellIgnored() {
        return mIgnoreBellCharacter;
    }

    public void setIgnoreBellCharacter(Context context, boolean newValue) {
        mIgnoreBellCharacter = newValue;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_IGNORE_BELL, newValue).apply();
    }

    public void updateDataVersion(Context context) {
        mDataVersion = BuildConfig.VERSION_CODE;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_DATA_VERSION, mDataVersion).apply();
    }

    public int getDataVersion() {
        return mDataVersion;
    }
}
