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

    // Reflection
    private static final String TARGET_CLASS = "android.net.ip.IpServer";
    private static final String TARGET_METHOD = "requestIpv4Address";
    private static final Class<?>[] TARGET_METHOD_PARAM = new Class[]{int.class, boolean.class};
    private static final String TARGET_FIELD = "mInterfaceType";

    // Config
    private static final Map<Integer, Config> CONFIG = Map.of(
            0, new Config("TETHERING_WIFI", "192.168.55.1/24"),
            1, new Config("TETHERING_USB", "192.168.55.1/24"),
            2, new Config("TETHERING_BLUETOOTH", "192.168.55.1/24"),
            3, new Config("TETHERING_WIFI_P2P", "192.168.55.1/24"),
            4, new Config("TETHERING_NCM", "192.168.55.1/24"),
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
            startHook(param);
        } catch (ClassNotFoundException e) {
            log(String.format("cannot load [ %s ] in package: [ %s ], Skip.", TARGET_CLASS, pn));
        }
    }

    private void startHook(PackageLoadedParam param) throws ClassNotFoundException {
        ClassLoader classLoader = param.getClassLoader();
        Class<?> targetClass = classLoader.loadClass(TARGET_CLASS);
        if (targetClass == null) {
            log(String.format("class not found: %s", TARGET_CLASS));
            return;
        }
        Method method;
        try {
            // requestIpv4Address(final int scope, final boolean useLastAddress)
            method = targetClass.getDeclaredMethod(TARGET_METHOD, TARGET_METHOD_PARAM);
        } catch (NoSuchMethodException e) {
            log(String.format("method not found: %s", TARGET_METHOD));
            return;
        }

        log(String.format("hooking method: %s", method.getName()));
        hook(method, MyHooker.class);
    }

    @XposedHooker
    private static class MyHooker implements Hooker {
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

    private static class Config {
        public String tetheringName;
        public String ipRange;

        public Config(String tetheringName, String ipRange) {
            this.tetheringName = tetheringName;
            this.ipRange = ipRange;
        }
    }
}