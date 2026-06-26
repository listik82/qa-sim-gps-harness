package com.qa.simgps;

import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class SimGpsHook implements IXposedHookLoadPackage {

    private static final int    SIM_STATE      = 5; // SIM_STATE_READY
    private static final String SIM_OPERATOR   = "310260";
    private static final String SIM_OP_NAME    = "T-Mobile";
    private static final String SIM_COUNTRY    = "us";
    private static final String LINE1_NUMBER   = "+12025551234";
    private static final String SIM_SERIAL     = "89012608521234567890";
    private static final String SUBSCRIBER_ID  = "310260123456789";

    private static volatile double LAT = 37.422000;
    private static volatile double LON = -122.084000;
    private static volatile double ALT = 12.0;
    private static volatile float  ACC = 5.0f;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpp) {
        if (!ScopeConfig.isInScope(lpp.packageName)) return;
        ScopeConfig.loadLocation(lpp);
        XposedBridge.log("[SimGpsHook] active in " + lpp.packageName);
        hookSim(lpp);
        hookGps(lpp);
        hookGmsFused(lpp);
    }

    private void hookSim(final LoadPackageParam lpp) {
        Class<?> tm = android.telephony.TelephonyManager.class;
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
        ret(tm, "getPhoneType", 1, int.class);
        ret(tm, "isNetworkRoaming", false);
    }

    private void hookGps(final LoadPackageParam lpp) {
        // last known location
        XposedHelpers.findAndHookMethod(LocationManager.class, "getLastKnownLocation",
                String.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(buildLocation((String) p.args[0]));
                    }
                });

        // provider always enabled
        XposedHelpers.findAndHookMethod(LocationManager.class, "isProviderEnabled",
                String.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(true); }
                });

        // intercept ALL requestLocationUpdates overloads and feed fix to the listener
        for (Method m : LocationManager.class.getDeclaredMethods()) {
            if (!m.getName().equals("requestLocationUpdates")
                && !m.getName().equals("requestSingleUpdate")) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        for (Object a : p.args) {
                            if (a instanceof android.location.LocationListener) {
                                deliverTo(a); break;
                            }
                        }
                    }
                });
            } catch (Throwable t) { XposedBridge.log("[SimGpsHook] hook updates skip: " + t); }
        }

        // rewrite Location getters (covers fused/GMS Location objects too)
        XposedHelpers.findAndHookMethod(Location.class, "getLatitude", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(LAT); } });
        XposedHelpers.findAndHookMethod(Location.class, "getLongitude", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(LON); } });
        XposedHelpers.findAndHookMethod(Location.class, "getAltitude", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(ALT); } });
        XposedHelpers.findAndHookMethod(Location.class, "getAccuracy", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(ACC); } });
        XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(false); } });
        if (Build.VERSION.SDK_INT >= 31) {
            try { XposedHelpers.findAndHookMethod(Location.class, "isMock", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(false); } });
            } catch (Throwable ignored) {}
        }
    }


    /** Best-effort Google Play Services (FusedLocationProvider) hooks. */
    private void hookGmsFused(final LoadPackageParam lpp) {
        // LocationResult: returned to LocationCallback.onLocationResult(...)
        try {
            Class<?> lr = XposedHelpers.findClass("com.google.android.gms.location.LocationResult", lpp.classLoader);
            XposedHelpers.findAndHookMethod(lr, "getLastLocation", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    p.setResult(buildLocation(LocationManager.GPS_PROVIDER));
                    XposedBridge.log("[SimGpsHook] GMS LocationResult.getLastLocation spoofed");
                }
            });
            XposedHelpers.findAndHookMethod(lr, "getLocations", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    p.setResult(Collections.singletonList(buildLocation(LocationManager.GPS_PROVIDER)));
                }
            });
        } catch (Throwable t) { XposedBridge.log("[SimGpsHook] no GMS LocationResult: " + t); }

        // FusedLocationProviderClient.getLastLocation(): resolve the Task with our fix
        try {
            Class<?> fused = XposedHelpers.findClass("com.google.android.gms.location.FusedLocationProviderClient", lpp.classLoader);
            for (Method m : fused.getMethods()) {
                if (m.getName().equals("getLastLocation") && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object task = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("com.google.android.gms.tasks.Tasks", lpp.classLoader),
                                    "forResult", buildLocation(LocationManager.GPS_PROVIDER));
                                p.setResult(task);
                                XposedBridge.log("[SimGpsHook] GMS getLastLocation Task spoofed");
                            } catch (Throwable t) { XposedBridge.log("[SimGpsHook] GMS task fail: " + t); }
                        }
                    });
                }
            }
        } catch (Throwable t) { XposedBridge.log("[SimGpsHook] no GMS FusedClient: " + t); }
    }

    private void deliverTo(final Object listener) {
        if (listener == null) return;
        final Location loc = buildLocation(LocationManager.GPS_PROVIDER);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { XposedHelpers.callMethod(listener, "onLocationChanged", loc); }
                catch (Throwable t) { XposedBridge.log("[SimGpsHook] deliver fail: " + t); }
            }
        });
    }

    private Location buildLocation(String provider) {
        Location l = new Location(provider == null ? LocationManager.GPS_PROVIDER : provider);
        l.setLatitude(LAT); l.setLongitude(LON); l.setAltitude(ALT); l.setAccuracy(ACC);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= 26) { try { l.setBearingAccuracyDegrees(1f); l.setSpeedAccuracyMetersPerSecond(1f); l.setVerticalAccuracyMeters(1f); } catch (Throwable ignored) {} }
        return l;
    }

    private void ret(Class<?> c, String method, final Object value, Class<?>... params) {
        try {
            XposedHelpers.findAndHookMethod(c, method, append(params, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    p.setResult(value);
                    XposedBridge.log("[SimGpsHook] " + method + "() -> " + value);
                }
            }));
        } catch (Throwable t) { XposedBridge.log("[SimGpsHook] skip " + method + ": " + t); }
    }
    private Object[] append(Class<?>[] params, XC_MethodHook cb) {
        Object[] a = new Object[params.length + 1];
        System.arraycopy(params, 0, a, 0, params.length); a[params.length] = cb; return a;
    }
    static void setLocation(double lat, double lon, double alt, float acc) { LAT=lat; LON=lon; ALT=alt; ACC=acc; }
}
