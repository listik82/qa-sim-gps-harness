package com.qa.simgps;

import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.telephony.TelephonyManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * QA/Test harness: presents a fake "ready" SIM and a deterministic GPS fix
 * to the app(s) under test on a SIM-less device or emulator.
 *
 * Scope it to YOUR test packages only (see assets/scope.txt). Do not run it
 * system-wide.
 */
public class SimGpsHook implements IXposedHookLoadPackage {

    // ---- Fake SIM identity (use clearly-test values) ----
    private static final int    SIM_STATE      = TelephonyManager.SIM_STATE_READY; // 5
    private static final String SIM_OPERATOR   = "00101";   // Test PLMN (MCC 001 / MNC 01)
    private static final String SIM_OP_NAME    = "QA-Test-Net";
    private static final String SIM_COUNTRY    = "us";
    private static final String LINE1_NUMBER   = "+15555550100"; // reserved test range
    private static final String SIM_SERIAL     = "89014103211118510720"; // sample ICCID-shaped
    private static final String SUBSCRIBER_ID  = "001010123456789";      // sample IMSI-shaped

    // ---- Fake GPS fix (override at runtime via assets/location.txt if present) ----
    private static volatile double LAT  = 37.422000;   // default: a known test point
    private static volatile double LON  = -122.084000;
    private static volatile double ALT  = 12.0;
    private static volatile float  ACC  = 5.0f;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpp) {
        if (!ScopeConfig.isInScope(lpp.packageName)) return;
        ScopeConfig.loadLocation(lpp);
        XposedBridge.log("[SimGpsHook] active in " + lpp.packageName);
        hookSim(lpp);
        hookGps(lpp);
    }

    // ---------------- SIM / Telephony ----------------
    private void hookSim(final LoadPackageParam lpp) {
        Class<?> tm = TelephonyManager.class;
        ret(tm, "getSimState", SIM_STATE, int.class);
        ret(tm, "getSimOperator", SIM_OPERATOR);
        ret(tm, "getSimOperatorName", SIM_OP_NAME);
        ret(tm, "getNetworkOperator", SIM_OPERATOR);
        ret(tm, "getNetworkOperatorName", SIM_OP_NAME);
        ret(tm, "getSimCountryIso", SIM_COUNTRY);
        ret(tm, "getNetworkCountryIso", SIM_COUNTRY);
        ret(tm, "getSimSerialNumber", SIM_SERIAL);
        ret(tm, "getSubscriberId", SUBSCRIBER_ID);
        ret(tm, "getLine1Number", LINE1_NUMBER);
        ret(tm, "getPhoneType", TelephonyManager.PHONE_TYPE_GSM, int.class);
        ret(tm, "isNetworkRoaming", false);
    }

    // ---------------- GPS / Location ----------------
    private void hookGps(final LoadPackageParam lpp) {
        // Make LocationManager hand back our fabricated Location for last-known fixes.
        XposedHelpers.findAndHookMethod(LocationManager.class, "getLastKnownLocation",
                String.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        String provider = (String) p.args[0];
                        p.setResult(buildLocation(provider));
                    }
                });

        // Rewrite Location getters so callbacks/listeners also see test coordinates.
        XposedHelpers.findAndHookMethod(Location.class, "getLatitude",
                replace(() -> LAT));
        XposedHelpers.findAndHookMethod(Location.class, "getLongitude",
                replace(() -> LON));
        XposedHelpers.findAndHookMethod(Location.class, "getAltitude",
                replace(() -> ALT));
        XposedHelpers.findAndHookMethod(Location.class, "getAccuracy",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(ACC); }
                });

        // For QA you usually want the fix to look like a normal fix, not a mock.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            XposedHelpers.findAndHookMethod(Location.class, "isMock",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(false); }
                    });
        }
        XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(false); }
                });
    }

    private Location buildLocation(String provider) {
        Location l = new Location(provider == null ? LocationManager.GPS_PROVIDER : provider);
        l.setLatitude(LAT);
        l.setLongitude(LON);
        l.setAltitude(ALT);
        l.setAccuracy(ACC);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        return l;
    }

    // ---- small helpers ----
    interface DoubleSupplier { double get(); }
    private XC_MethodHook replace(final DoubleSupplier s) {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(s.get()); }
        };
    }
    private void ret(Class<?> c, String method, final Object value, Class<?>... params) {
        try {
            XposedHelpers.findAndHookMethod(c, method, append(params, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(value); }
            }));
        } catch (Throwable t) { XposedBridge.log("[SimGpsHook] skip " + method + ": " + t); }
    }
    private Object[] append(Class<?>[] params, XC_MethodHook cb) {
        Object[] a = new Object[params.length + 1];
        System.arraycopy(params, 0, a, 0, params.length);
        a[params.length] = cb;
        return a;
    }
    // setters for runtime override
    static void setLocation(double lat, double lon, double alt, float acc) {
        LAT = lat; LON = lon; ALT = alt; ACC = acc;
    }
}
