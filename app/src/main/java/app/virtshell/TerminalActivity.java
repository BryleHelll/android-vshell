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

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.virtshell.emulator.TerminalSession;
import app.virtshell.emulator.TerminalSession.SessionChangedCallback;
import app.virtshell.terminal_view.TerminalView;

public final class TerminalActivity extends Activity implements ServiceConnection {

    private static final int CONTEXTMENU_PASTE_ID = 1;
    private static final int CONTEXTMENU_SHOW_HELP = 2;
    private static final int CONTEXTMENU_SELECT_URLS = 3;
    private static final int CONTEXTMENU_RESET_TERMINAL_ID = 4;
    private static final int CONTEXTMEMU_SHUTDOWN = 5;
    private static final int CONTEXTMENU_TOGGLE_IGNORE_BELL = 6;

    private final int MAX_FONTSIZE = 256;
    private int MIN_FONTSIZE;
    private static int currentFontSize = -1;

    /**
     * Global state of application settings.
     */
    TerminalPreferences mSettings;

    /**
     * The main view of the activity showing the terminal. Initialized in onCreate().
     */
    TerminalView mTerminalView;

    /**
     * The view of Extra Keys Row. Initialized in onCreate() and used by InputDispatcher.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The connection to the {@link TerminalService}. Requested in {@link #onCreate(Bundle)}
     * with a call to {@link #bindService(Intent, ServiceConnection, int)}, obtained and stored
     * in {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TerminalService mTermService;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of
     * the terminal view at the time, so if the session causing a change is not in the foreground
     * it should probably be treated as background.
     */
    private boolean mIsVisible;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.main);

        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setOnKeyListener(new InputDispatcher(this));
        mTerminalView.setKeepScreenOn(true);
        mTerminalView.requestFocus();
        setupTerminalStyle();
        registerForContextMenu(mTerminalView);

        mSettings = new TerminalPreferences(this);
        mExtraKeysView = findViewById(R.id.extra_keys);
        if (mSettings.isExtraKeysEnabled()) {
            mExtraKeysView.setVisibility(View.VISIBLE);
        }

        if (mSettings.isFirstRun()) {
            new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(R.string.firstrun_dialog_title)
                .setMessage(R.string.firstrun_dialog_desc)
                .setPositiveButton(R.string.ok_label, (dialog, which) -> {
                    dialog.dismiss();
                    mSettings.completedFirstRun(this);
                    startApplication();
            }).show();
        } else {
            startApplication();
        }
    }

    /**
     * Check for storage permission and start service.
     */
    private void startApplication() {
        boolean hasStoragePermission = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // On Android 11 we need to deal with MANAGE_EXTERNAL_STORAGE permission to overcome
            // the scoped storage restrictions.
            // Ref: https://developer.android.com/about/versions/11/privacy/storage#all-files-access
            // Ref: https://developer.android.com/training/data-storage/manage-all-files
            if (Environment.isExternalStorageManager()) {
                hasStoragePermission = true;
            }
        } else {
            // Otherwise use a regular permission WRITE_EXTERNAL_STORAGE.
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
                hasStoragePermission = true;
            }
        }

        // Ensure that application can manage storage.
        if (!hasStoragePermission) {
            startActivity(new Intent(this, StoragePermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }

        // Start the service and make it run regardless of who is bound to it:
        Intent serviceIntent = new Intent(this, TerminalService.class);
        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0)) {
            throw new RuntimeException("bindService() failed");
        }
    }

    /**
     * Reset terminal font size to the optimal value and set custom text font.
     */
    private void setupTerminalStyle() {
        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
            getResources().getDisplayMetrics());
        int defaultFontSize = Math.round(7.5f * dipInPixels);

        // Make it divisible by 2 since that is the minimal adjustment step:
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        if (TerminalActivity.currentFontSize == -1) {
            TerminalActivity.currentFontSize = defaultFontSize;
        }

        // This is a bit arbitrary and sub-optimal. We want to give a sensible default for minimum
        // font size to prevent invisible text due to zoom be mistake:
        MIN_FONTSIZE = (int) (4f * dipInPixels);

        TerminalActivity.currentFontSize = Math.max(MIN_FONTSIZE,
            Math.min(TerminalActivity.currentFontSize, MAX_FONTSIZE));
        mTerminalView.setTextSize(TerminalActivity.currentFontSize);

        // Use bundled in app monospace font.
        mTerminalView.setTypeface(Typeface.createFromAsset(getAssets(), "console_font.ttf"));
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsVisible = true;

        if (mTermService != null) {
            TerminalSession session = mTermService.getSession();
            if (session != null) {
                mTerminalView.attachSession(session);
            }
        }

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal:
        mTerminalView.onScreenUpdated();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mIsVisible = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTermService != null) {
            // Do not leave service with references to activity.
            mTermService.mSessionChangeCallback = null;
            mTermService = null;
            unbindService(this);
        }
    }

    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which
     * will cause a call to this callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermService = ((TerminalService.LocalBinder) service).service;

        mTermService.mSessionChangeCallback = new SessionChangedCallback() {
            @Override
            public void onTextChanged(TerminalSession changedSession) {
                if (!mIsVisible) return;
                if (mTerminalView.getCurrentSession() == changedSession) {
                    mTerminalView.onScreenUpdated();
                }
            }

            @Override
            public void onSessionFinished(final TerminalSession finishedSession) {
                // Needed for resetting font size on next application launch
                // otherwise it will be reset only after force-closing.
                TerminalActivity.currentFontSize = -1;

                // Do not immediately terminate service in debug builds.
                if (!BuildConfig.DEBUG) {
                    if (mTermService.mWantsToStop) {
                        // The service wants to stop as soon as possible.
                        finish();
                        return;
                    }
                    mTermService.terminateService();
                }
            }

            @Override
            public void onClipboardText(TerminalSession session, String text) {
                if (!mIsVisible) return;
                ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(new ClipData(null,
                        new String[]{"text/plain"}, new ClipData.Item(text)));
                }
            }

            @Override
            public void onBell(TerminalSession session) {
                if (!mIsVisible || mSettings.isBellIgnored()) {
                    return;
                }

                Bell.getInstance(TerminalActivity.this).doBell();
            }
        };

        if (mTermService.getSession() == null) {
            if (mIsVisible) {
                Installer.setupIfNeeded(TerminalActivity.this, () -> {
                    if (mTermService == null) return; // Activity might have been destroyed.

                    try {
                        TerminalSession session = startQemu();
                        mTerminalView.attachSession(session);
                        mTermService.setSession(session);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finish();
            }
        } else {
            mTerminalView.attachSession(mTermService.getSession());
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Respect being stopped from the TerminalService notification action.
        finish();
    }

    /**
     * Create a terminal session running QEMU.
     * @return TerminalSession instance.
     */
    private TerminalSession startQemu() {
        ArrayList<String> environment = new ArrayList<>();
        Context appContext = this;

        String runtimeDataPath = Config.getDataDirectory(appContext);

        environment.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"));
        environment.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"));
        environment.add("APP_RUNTIME_DIR=" + runtimeDataPath);
        environment.add("LANG=en_US.UTF-8");
        environment.add("HOME=" + runtimeDataPath);
        environment.add("PATH=/system/bin");
        environment.add("TMPDIR=" + appContext.getCacheDir().getAbsolutePath());

        // Used by QEMU internal DNS.
        environment.add("CONFIG_QEMU_DNS=" + Config.QEMU_UPSTREAM_DNS);

        // Variables present on Android 10 or higher.
        String[] androidExtra = {
            "ANDROID_ART_ROOT",
            "ANDROID_I18N_ROOT",
            "ANDROID_RUNTIME_ROOT",
            "ANDROID_TZDATA_ROOT"
        };
        for (String var : androidExtra) {
            String value = System.getenv(var);
            if (value != null) {
                environment.add(var + "=" + value);
            }
        }

        // QEMU is loaded as shared library, however options are being provided as
        // command line arguments.
        ArrayList<String> processArgs = new ArrayList<>();

        // Fake argument to provide argv[0].
        processArgs.add("vShell");

        // Path to directory with firmware & keymap files.
        processArgs.addAll(Arrays.asList("-L", runtimeDataPath));

        // Emulate CPU with max feature set.
        processArgs.addAll(Arrays.asList("-cpu", "max"));

        // Determine safe values for VM RAM allocation.
        ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        if (am != null) {
            am.getMemoryInfo(memInfo);
            // 32% of host memory will be used for QEMU emulated RAM.
            int safeRam = (int) (memInfo.totalMem * 0.32 / 1048576);
            // 8% of host memory will be used for QEMU TCG buffer.
            int safeTcg = (int) (memInfo.totalMem * 0.08 / 1048576);
            processArgs.addAll(Arrays.asList("-m", safeRam + "M", "-accel", "tcg,tb-size=" + safeTcg));
        } else {
            // Fallback.
            Log.e(Config.APP_LOG_TAG, "failed to determine size of host memory");
            processArgs.addAll(Arrays.asList("-m", "256M", "-accel", "tcg,tb-size=64"));
        }

        // Do not create default devices.
        processArgs.add("-nodefaults");

        // SCSI CD-ROM(s) and HDD(s).
        processArgs.addAll(Arrays.asList("-drive", "file=" + runtimeDataPath + "/" + Config.CDROM_IMAGE_NAME + ",if=none,media=cdrom,index=0,id=cd0"));
        processArgs.addAll(Arrays.asList("-drive", "file=" + runtimeDataPath + "/" + Config.HDD_IMAGE_NAME + ",if=none,index=2,discard=unmap,detect-zeroes=unmap,cache=writeback,id=hd0"));
        processArgs.addAll(Arrays.asList("-device", "virtio-scsi-pci,id=virtio-scsi-pci0"));
        processArgs.addAll(Arrays.asList("-device", "scsi-cd,bus=virtio-scsi-pci0.0,id=scsi-cd0,drive=cd0"));
        processArgs.addAll(Arrays.asList("-device", "scsi-hd,bus=virtio-scsi-pci0.0,id=scsi-hd0,drive=hd0"));

        // Try to boot from HDD.
        // Default HDD setup has a valid MBR allowing to try next drive in case if OS not
        // installed, so CD-ROM is going to be actually booted.
        processArgs.addAll(Arrays.asList("-boot", "c,menu=on"));

        // Setup random number generator.
        processArgs.addAll(Arrays.asList("-object", "rng-random,filename=/dev/urandom,id=rng0"));
        processArgs.addAll(Arrays.asList("-device", "virtio-rng-pci,rng=rng0,id=virtio-rng-pci0"));

        // Networking.
        processArgs.addAll(Arrays.asList("-netdev", "user,id=vmnic0"));
        processArgs.addAll(Arrays.asList("-device", "virtio-net-pci,netdev=vmnic0,id=virtio-net-pci0"));

        // Access to shared storage.
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            processArgs.addAll(Arrays.asList("-fsdev", "local,security_model=none,id=fsdev0,multidevs=remap,path=/storage/self/primary"));
            processArgs.addAll(Arrays.asList("-device", "virtio-9p-pci,fsdev=fsdev0,mount_tag=host_storage,id=virtio-9p-pci0"));
        }

        // We need only monitor & serial consoles.
        processArgs.add("-nographic");

        // Disable parallel port.
        processArgs.addAll(Arrays.asList("-parallel", "none"));

        // Serial console.
        processArgs.addAll(Arrays.asList("-chardev", "stdio,id=serial0,mux=off,signal=off"));
        processArgs.addAll(Arrays.asList("-serial", "chardev:serial0"));

        Log.i(Config.APP_LOG_TAG, "initiating QEMU session with following arguments: "
            + processArgs.toString());

        TerminalSession session = new TerminalSession(processArgs.toArray(new String[0]),
            environment.toArray(new String[0]), Config.getDataDirectory(appContext), mTermService);

        // Notify user that booting can take a while.
        Toast.makeText(this, R.string.toast_boot_notification, Toast.LENGTH_LONG).show();

        return session;
    }

    /**
     * Hook system menu to show context menu instead.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        menu.add(Menu.NONE, CONTEXTMENU_SHOW_HELP, Menu.NONE, R.string.menu_show_help);
        menu.add(Menu.NONE, CONTEXTMENU_SELECT_URLS, Menu.NONE, R.string.menu_select_urls);
        menu.add(Menu.NONE, CONTEXTMENU_RESET_TERMINAL_ID, Menu.NONE, R.string.menu_reset_terminal);
        menu.add(Menu.NONE, CONTEXTMEMU_SHUTDOWN, Menu.NONE, R.string.menu_shutdown);
        menu.add(Menu.NONE, CONTEXTMENU_TOGGLE_IGNORE_BELL, Menu.NONE, R.string.menu_toggle_ignore_bell).setCheckable(true).setChecked(mSettings.isBellIgnored());
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case CONTEXTMENU_PASTE_ID:
                doPaste();
                return true;
            case CONTEXTMENU_SHOW_HELP:
                startActivity(new Intent(this, HelpActivity.class));
                return true;
            case CONTEXTMENU_SELECT_URLS:
                showUrlSelection();
                return true;
            case CONTEXTMENU_RESET_TERMINAL_ID:
                TerminalSession session = mTerminalView.getCurrentSession();
                if (session != null) {
                    session.reset(true);
                    Toast.makeText(this, R.string.toast_reset_terminal,
                        Toast.LENGTH_SHORT).show();
                }
                return true;
            case CONTEXTMEMU_SHUTDOWN:
                if (mTermService != null) {
                    mTermService.terminateService();
                }
                return true;
            case CONTEXTMENU_TOGGLE_IGNORE_BELL:
                mSettings.setIgnoreBellCharacter(this, !mSettings.isBellIgnored());
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    /**
     * Paste text from clipboard.
     */
    public void doPaste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        if (clipboard != null) {
            ClipData clipData = clipboard.getPrimaryClip();

            if (clipData == null) {
                return;
            }

            CharSequence paste = clipData.getItemAt(0).coerceToText(this);
            if (!TextUtils.isEmpty(paste)) {
                TerminalSession currentSession = mTerminalView.getCurrentSession();

                if (currentSession != null) {
                    currentSession.getEmulator().paste(paste.toString());
                }
            }
        }
    }

    /**
     * Extract URLs from the current transcript and show them in dialog.
     */
    public void showUrlSelection() {
        TerminalSession currentSession = mTerminalView.getCurrentSession();

        if (currentSession == null) {
            return;
        }

        String text = currentSession.getEmulator().getScreen().getTranscriptText();
        LinkedHashSet<CharSequence> urlSet = extractUrls(text);

        if (urlSet.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_urls_found, Toast.LENGTH_SHORT).show();
            return;
        }

        final CharSequence[] urls = urlSet.toArray(new CharSequence[0]);
        Collections.reverse(Arrays.asList(urls)); // Latest first.

        // Click to copy url to clipboard:
        final AlertDialog dialog = new AlertDialog.Builder(TerminalActivity.this).setItems(urls, (di, which) -> {
            String url = (String) urls[which];
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(url)));
                Toast.makeText(this, R.string.toast_url_copied, Toast.LENGTH_SHORT).show();
            }
        }).setTitle(R.string.select_url_dialog_title).create();

        // Long press to open URL:
        dialog.setOnShowListener(di -> {
            ListView lv = dialog.getListView(); // this is a ListView with your "buds" in it
            lv.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                String url = (String) urls[position];

                // Disable handling of 'file://' urls since this may
                // produce android.os.FileUriExposedException.
                if (!url.startsWith("file://")) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    try {
                        startActivity(i, null);
                    } catch (ActivityNotFoundException e) {
                        // If no applications match, Android displays a system message.
                        startActivity(Intent.createChooser(i, null));
                    }
                } else {
                    Toast.makeText(this, R.string.toast_bad_url, Toast.LENGTH_SHORT).show();
                }

                return true;
            });
        });

        dialog.show();
    }

    /**
     * Extract URLs from the given text.
     */
    @SuppressWarnings("StringBufferReplaceableByString")
    private static LinkedHashSet<CharSequence> extractUrls(String text) {
        StringBuilder regex_sb = new StringBuilder();

        regex_sb.append("(");                       // Begin first matching group.
        regex_sb.append("(?:");                     // Begin scheme group.
        regex_sb.append("dav|");                    // The DAV proto.
        regex_sb.append("dict|");                   // The DICT proto.
        regex_sb.append("dns|");                    // The DNS proto.
        regex_sb.append("file|");                   // File path.
        regex_sb.append("finger|");                 // The Finger proto.
        regex_sb.append("ftp(?:s?)|");              // The FTP proto.
        regex_sb.append("git|");                    // The Git proto.
        regex_sb.append("gopher|");                 // The Gopher proto.
        regex_sb.append("http(?:s?)|");             // The HTTP proto.
        regex_sb.append("imap(?:s?)|");             // The IMAP proto.
        regex_sb.append("irc(?:[6s]?)|");           // The IRC proto.
        regex_sb.append("ip[fn]s|");                // The IPFS proto.
        regex_sb.append("ldap(?:s?)|");             // The LDAP proto.
        regex_sb.append("pop3(?:s?)|");             // The POP3 proto.
        regex_sb.append("redis(?:s?)|");            // The Redis proto.
        regex_sb.append("rsync|");                  // The Rsync proto.
        regex_sb.append("rtsp(?:[su]?)|");          // The RTSP proto.
        regex_sb.append("sftp|");                   // The SFTP proto.
        regex_sb.append("smb(?:s?)|");              // The SAMBA proto.
        regex_sb.append("smtp(?:s?)|");             // The SMTP proto.
        regex_sb.append("svn(?:(?:\\+ssh)?)|");     // The Subversion proto.
        regex_sb.append("tcp|");                    // The TCP proto.
        regex_sb.append("telnet|");                 // The Telnet proto.
        regex_sb.append("tftp|");                   // The TFTP proto.
        regex_sb.append("udp|");                    // The UDP proto.
        regex_sb.append("vnc|");                    // The VNC proto.
        regex_sb.append("ws(?:s?)");                // The Websocket proto.
        regex_sb.append(")://");                    // End scheme group.
        regex_sb.append(")");                       // End first matching group.

        // Begin second matching group.
        regex_sb.append("(");

        // User name and/or password in format 'user:pass@'.
        regex_sb.append("(?:\\S+(?::\\S*)?@)?");

        // Begin host group.
        regex_sb.append("(?:");

        // IP address (from http://www.regular-expressions.info/examples.html).
        regex_sb.append("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|");

        // Host name or domain.
        regex_sb.append("(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff]{2,})))?|");

        // Just path. Used in case of 'file://' scheme.
        regex_sb.append("/(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)");

        // End host group.
        regex_sb.append(")");

        // Port number.
        regex_sb.append("(?::\\d{1,5})?");

        // Resource path with optional query string.
        regex_sb.append("(?:/[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // Fragment.
        regex_sb.append("(?:#[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // End second matching group.
        regex_sb.append(")");

        final Pattern urlPattern = Pattern.compile(
            regex_sb.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        Matcher matcher = urlPattern.matcher(text);

        while (matcher.find()) {
            int matchStart = matcher.start(1);
            int matchEnd = matcher.end();
            String url = text.substring(matchStart, matchEnd);
            urlSet.add(url);
        }

        return urlSet;
    }

    /**
     * Change terminal font size.
     */
    public void changeFontSize(boolean increase) {
        TerminalActivity.currentFontSize += (increase ? 1 : -1) * 2;
        TerminalActivity.currentFontSize = Math.max(MIN_FONTSIZE, Math.min(TerminalActivity.currentFontSize, MAX_FONTSIZE));
        mTerminalView.setTextSize(TerminalActivity.currentFontSize);
    }

    /**
     * Toggle extra keys layout.
     */
    public void toggleShowExtraKeys() {
        View extraKeys = findViewById(R.id.extra_keys);
        boolean showNow = mSettings.toggleShowExtraKeys(TerminalActivity.this);
        extraKeys.setVisibility(showNow ? View.VISIBLE : View.GONE);
    }
}
