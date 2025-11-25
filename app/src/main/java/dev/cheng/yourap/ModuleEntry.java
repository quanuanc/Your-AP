package dev.cheng.yourap;

import android.net.LinkAddress;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

@SuppressWarnings({"PrivateApi", "BlockedPrivateApi", "JavaReflectionMemberAccess"})
public class ModuleEntry extends XposedModule {
    private static XposedModule module;

    private static class Config {
        public String tetheringName;
        public String ipRange;

        public Config(String tetheringName, String ipRange) {
            this.tetheringName = tetheringName;
            this.ipRange = ipRange;
        }
    }

    // Static IP
    private static final String TARGET_CLASS = "android.net.ip.IpServer";
    private static final String TARGET_METHOD = "requestIpv4Address";
    private static final String TARGET_FIELD = "mInterfaceType";

    // DNS disable 
    private static final String TETHER_MAIN_SM_CLASSES =
            "com.android.networkstack.tethering.Tethering$TetherMainSM";
    private static final String TURN_ON_MAIN_TETHER_SETTINGS = "turnOnMainTetherSettings";
    private static final String TETHERING_CLASS = "com.android.networkstack.tethering.Tethering";
    private static final String TETHER_CONFIG_PARCEL = "android.net.TetherConfigParcel";

    // Config
    private static final Map<Integer, Config> CONFIG = Map.of(
            0, new Config("TETHERING_WIFI", "192.168.54.1/24"),
            5, new Config("TETHERING_ETHERNET", "192.168.55.1/24")
    );

    public ModuleEntry(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        module = this;
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pn = param.getPackageName();

        log(String.format("package loaded: %s", pn));
        try {
            staticIpHook(param);
        } catch (ClassNotFoundException e) {
            log(String.format("cannot load [ %s ] in package: [ %s ], Skip.", TARGET_CLASS, pn));
        }
        try {
            tetheringHook(param);
        } catch (ClassNotFoundException e) {
            log(String.format("cannot load [ %s ] in package: [ %s ], Skip.", "NetdUtils*", pn));
        }
    }

    private void staticIpHook(PackageLoadedParam param) throws ClassNotFoundException {
        ClassLoader classLoader = param.getClassLoader();
        Class<?> targetClass = classLoader.loadClass(TARGET_CLASS);
        if (targetClass == null) {
            log(String.format("class not found: %s", TARGET_CLASS));
            return;
        }
        Method method;
        try {
            // requestIpv4Address(final int scope, final boolean useLastAddress)
            method = targetClass.getDeclaredMethod(TARGET_METHOD, int.class, boolean.class);
        } catch (NoSuchMethodException e) {
            log(String.format("method not found: %s", TARGET_METHOD));
            return;
        }

        log(String.format("hooking method: %s", method.getName()));
        hook(method, TetheringIpRangeHook.class);
    }

    @XposedHooker
    private static class TetheringIpRangeHook implements Hooker {
        @BeforeInvocation
        public static void before(BeforeHookCallback callback) {         // Pre-hooking
            module.log("invoke hooker before method");
            Class<?> targetClass = callback.getMember().getDeclaringClass();
            int type = -1;
            try {
                Field declaredField = targetClass.getDeclaredField(TARGET_FIELD);
                declaredField.setAccessible(true);
                type = declaredField.getInt(callback.getThisObject());
            } catch (NoSuchFieldException | IllegalAccessException e) {
                module.log("exception in get field", e);
            }
            Config config = CONFIG.get(type);
            if (config == null) {
                module.log(String.format("type: %s not found in config", type));
                return;
            }
            module.log(String.format("detect interface [ %s ]", config.tetheringName));
            String ipRange = config.ipRange;
            LinkAddress result;
            try {
                Constructor<LinkAddress> constructor =
                        LinkAddress.class.getDeclaredConstructor(String.class);
                result = constructor.newInstance(ipRange);
            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException |
                     InstantiationException e) {
                module.log("exception while instance LinkAddress", e);
                return;
            }
            callback.returnAndSkip(result);
            module.log(String.format("success set [ %s ] for [ %s ]",
                    config.ipRange,
                    config.tetheringName)
            );
        }

    }

    private void tetheringHook(PackageLoadedParam param) throws ClassNotFoundException {
        ClassLoader loader = param.getClassLoader();
        Class<?> tetherSm;
        tetherSm = loader.loadClass(TETHER_MAIN_SM_CLASSES);
        if (tetherSm == null) {
            log("TetherMainSM not found");
            return;
        }
        Method method;
        try {
            method = tetherSm.getDeclaredMethod(TURN_ON_MAIN_TETHER_SETTINGS);
            method.setAccessible(true);
        } catch (NoSuchMethodException e) {
            log(String.format("method not found: %s", TURN_ON_MAIN_TETHER_SETTINGS));
            return;
        }
        log(String.format("hooking method: %s", method.getName()));
        hook(method, TetheringHook.class);
    }

    @XposedHooker
    private static class TetheringHook implements Hooker {
        @BeforeInvocation
        public static void before(BeforeHookCallback callback) {
            module.log(String.format("invoke hooker before %s", TURN_ON_MAIN_TETHER_SETTINGS));
            boolean handled = false;
            try {
                handled = handleTurnOnMainTetherSettings(callback.getThisObject());
            } catch (Throwable t) {
                module.log("override failed, keep original", t);
            }
            if (handled) {
                callback.returnAndSkip(true);
                module.log("success disable dnsmasq");
            }
        }
    }

    private static boolean handleTurnOnMainTetherSettings(Object target) {
        Object outer = getOuterInstance(target);
        Object holder = outer != null ? outer : target;
        Object config = getField(holder, "mConfig");
        Object netd = getField(holder, "mNetd");
        Object logObj = getField(holder, "mLog");
        Object ipfwdErrorState = getField(holder, "mSetIpForwardingEnabledErrorState");
        Object startErrorState = getField(holder, "mStartTetheringErrorState");
        if (config == null || netd == null) {
            module.log("missing config or netd");
            return false;
        }

        final String tag = getTetheringTag(holder);

        try {
            invoke(netd, "ipfwdEnableForwarding", new Class[]{String.class}, tag);
        } catch (Throwable e) {
            logError(logObj, e);
            transitionTo(target, ipfwdErrorState);
            return true;
        }

        final String[] dhcpRanges = buildDhcpRanges(config);
        try {
            tetherStartWithConfig(netd, dhcpRanges, target.getClass().getClassLoader());
        } catch (Throwable first) {
            try {
                invoke(netd, "tetherStop", new Class[]{});
                tetherStartWithConfig(netd, dhcpRanges, target.getClass().getClassLoader());
            } catch (Throwable retry) {
                logError(logObj, retry);
                transitionTo(target, startErrorState);
                return true;
            }
        }

        logInfo(logObj, "SET main tether settings: ON (dns proxy disabled)");
        return true;
    }

    private static Object getOuterInstance(Object target) {
        if (target == null) return null;
        Class<?> cls = target.getClass();
        for (Field f : cls.getDeclaredFields()) {
            if (f.getName().startsWith("this$")) {
                try {
                    f.setAccessible(true);
                    Object outer = f.get(target);
                    if (outer != null) return outer;
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return null;
    }

    private static String[] buildDhcpRanges(Object config) {
        try {
            Method useLegacy = config.getClass().getDeclaredMethod("useLegacyDhcpServer");
            useLegacy.setAccessible(true);
            boolean legacy = (boolean) useLegacy.invoke(config);
            if (!legacy) return new String[0];
            Field legacyRanges = config.getClass().getDeclaredField("legacyDhcpRanges");
            legacyRanges.setAccessible(true);
            Object value = legacyRanges.get(config);
            if (value instanceof String[]) return (String[]) value;
        } catch (Exception e) {
            module.log("dhcp ranges fallback to empty", e);
        }
        return new String[0];
    }

    private static void tetherStartWithConfig(Object netd,
                                              String[] dhcpRanges, ClassLoader loader)
            throws Exception {
        Class<?> parcelClass = Class.forName(TETHER_CONFIG_PARCEL, true, loader);
        Object parcel = parcelClass.getDeclaredConstructor().newInstance();
        Field useLegacyField = parcelClass.getDeclaredField("usingLegacyDnsProxy");
        Field dhcpField = parcelClass.getDeclaredField("dhcpRanges");
        useLegacyField.setBoolean(parcel, false);
        dhcpField.set(parcel, dhcpRanges);

        Method start = netd.getClass().getMethod("tetherStartWithConfiguration", parcelClass);
        start.setAccessible(true);
        start.invoke(netd, parcel);
    }

    private static void transitionTo(Object target, Object state) {
        if (state == null) return;
        try {
            Class<?> iState = Class.forName("com.android.internal.util.IState");
            Method transition = target.getClass().getDeclaredMethod("transitionTo", iState);
            transition.setAccessible(true);
            transition.invoke(target, state);
        } catch (Exception e) {
            module.log("failed to transition state", e);
        }
    }

    private static Object getField(Object target, String name) {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static void invoke(Object target, String name, Class<?>[] params, Object... args)
            throws Exception {
        Method m = target.getClass().getMethod(name, params);
        m.setAccessible(true);
        m.invoke(target, args);
    }

    private static void logError(Object logObj, Throwable t) {
        if (logObj == null) {
            module.log("tethering hook error", t);
            return;
        }
        try {
            Method eMethod = logObj.getClass().getMethod("e", Object.class);
            eMethod.setAccessible(true);
            eMethod.invoke(logObj, t);
        } catch (Exception ignored) {
            module.log("tethering hook error", t);
        }
    }

    private static void logInfo(Object logObj, String msg) {
        if (logObj == null) {
            module.log(msg);
            return;
        }
        try {
            Method logMethod = logObj.getClass().getMethod("log", String.class);
            logMethod.setAccessible(true);
            logMethod.invoke(logObj, msg);
        } catch (Exception ignored) {
            module.log(msg);
        }
    }

    private static String getTetheringTag(Object holder) {
        // Use the same ClassLoader as the tethering state machine to find TAG.
        ClassLoader loader = holder != null
                ? holder.getClass().getClassLoader()
                : ModuleEntry.class.getClassLoader();
        try {
            Class<?> tethering = Class.forName(TETHERING_CLASS, true, loader);
            Field tagField = tethering.getDeclaredField("TAG");
            tagField.setAccessible(true);
            Object value = tagField.get(null);
            return value == null ? "Tethering" : value.toString();
        } catch (ClassNotFoundException e) {
            // Fallback: avoid noisy stack traces when the class isn't visible in this loader.
            return holder != null ? holder.getClass().getSimpleName() : "Tethering";
        } catch (Exception e) {
            module.log("tethering tag", e);
            return "Tethering";
        }
    }

}