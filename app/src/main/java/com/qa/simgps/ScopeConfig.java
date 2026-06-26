package com.qa.simgps;

import android.content.res.XModuleResources;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Whitelist which test packages get spoofed, and load coordinates from a file. */
public class ScopeConfig {
    // EDIT THIS: only your app(s) under test.
    private static final Set<String> SCOPE = new HashSet<>(Arrays.asList(
            "com.google.android.gms",
            "com.google.android.gms"
    ));

    static boolean isInScope(String pkg) { return SCOPE.contains(pkg); }

    /**
     * Optional: read /data/local/tmp/qa_location.txt = "lat,lon,alt,acc"
     * so testers can move the fix without rebuilding the module.
     */
    static void loadLocation(LoadPackageParam lpp) {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/qa_location.txt");
            if (!f.exists()) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f)));
            String line = r.readLine();
            r.close();
            if (line == null) return;
            String[] s = line.trim().split(",");
            SimGpsHook.setLocation(
                Double.parseDouble(s[0]), Double.parseDouble(s[1]),
                s.length > 2 ? Double.parseDouble(s[2]) : 0,
                s.length > 3 ? Float.parseFloat(s[3]) : 5f);
            XposedBridge.log("[SimGpsHook] loaded location override: " + line);
        } catch (Throwable t) { XposedBridge.log("[SimGpsHook] no location override: " + t); }
    }
}
