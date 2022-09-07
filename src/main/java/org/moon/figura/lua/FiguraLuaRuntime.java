package org.moon.figura.lua;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.moon.figura.FiguraMod;
import org.moon.figura.avatars.Avatar;
import org.moon.figura.lua.api.AvatarAPI;
import org.moon.figura.lua.api.HostAPI;
import org.moon.figura.lua.api.RendererAPI;
import org.moon.figura.lua.api.action_wheel.ActionWheelAPI;
import org.moon.figura.lua.api.entity.EntityAPI;
import org.moon.figura.lua.api.event.EventsAPI;
import org.moon.figura.lua.api.keybind.KeybindAPI;
import org.moon.figura.lua.api.nameplate.NameplateAPI;
import org.moon.figura.lua.api.ping.PingAPI;
import org.moon.figura.lua.api.vanilla_model.VanillaModelAPI;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * One runtime per avatar
 */
public class FiguraLuaRuntime {

    //Global API instances
    //---------------------------------
    public EntityAPI<?> entityAPI;
    public EventsAPI events;
    public VanillaModelAPI vanilla_model;
    public KeybindAPI keybind;
    public HostAPI host;
    public NameplateAPI nameplate;
    public RendererAPI renderer;
    public ActionWheelAPI action_wheel;
    public AvatarAPI avatar_meta;

    public PingAPI ping;

    //---------------------------------

    public final Avatar owner;
    private final Globals userGlobals = new Globals();
    private final LuaValue setHookFunction;
    private final Map<String, String> scripts = new HashMap<>();
    private final Map<String, LuaValue> loadedScripts = new HashMap<>();
    public final LuaTypeManager typeManager = new LuaTypeManager();

    public FiguraLuaRuntime(Avatar avatar, Map<String, String> scripts) {
        this.owner = avatar;
        this.scripts.putAll(scripts);

        //Each user gets their own set of globals as well.
        userGlobals.load(new JseBaseLib());
        userGlobals.load(new Bit32Lib());
        userGlobals.load(new TableLib());
        userGlobals.load(new StringLib());
        userGlobals.load(new JseMathLib());

        LoadState.install(userGlobals);
        LuaC.install(userGlobals);

        userGlobals.load(new DebugLib());
        setHookFunction = userGlobals.get("debug").get("sethook");

        setupFiguraSandbox();

        FiguraAPIManager.setupTypesAndAPIs(this);

        loadExtraLibraries();

        LuaTable figuraMetatables = new LuaTable();
        typeManager.dumpMetatables(figuraMetatables);
        setGlobal("figuraMetatables", figuraMetatables);
    }

    public int run(String name, String src) {
        try {
            userGlobals.load(src, name, userGlobals).call();
            return 1;
        } catch (LuaError e) {
            FiguraLuaPrinter.sendLuaError(e, owner.entityName, owner.owner);
            return 0;
        }
    }

    public void registerClass(Class<?> clazz) {
        typeManager.generateMetatableFor(clazz);
    }

    public void setGlobal(String name, Object obj) {
        userGlobals.set(name, typeManager.javaToLua(obj));
    }

    public void setUser(Entity user) {
        entityAPI = EntityAPI.wrap(user);
        userGlobals.set("user", typeManager.javaToLua(entityAPI));
        userGlobals.set("player", userGlobals.get("user"));
    }

    public Entity getUser() {
        return entityAPI == null ? null : entityAPI.getEntity();
    }

    private void setupFiguraSandbox() {
        //actual sandbox file
        try (InputStream inputStream = FiguraMod.class.getResourceAsStream("/assets/" + FiguraMod.MOD_ID + "/scripts/sandbox.lua")) {
            if (inputStream == null) throw new IOException("Unable to get resource");
            userGlobals.load(new String(inputStream.readAllBytes()), "sandbox").call();
        } catch (Exception e) {
            error(new LuaError("Failed to load builtin sandbox script:\n" + e.getMessage()));
        }

        //read only string metatable
        LuaString.s_metatable = new ReadOnlyLuaTable(LuaString.s_metatable);
    }

    private final OneArgFunction requireFunction = new OneArgFunction() {
        @Override
        public LuaValue call(LuaValue arg) {
            return INIT_SCRIPT.apply(arg.checkjstring());
        }

        @Override
        public String tojstring() {
            return "function: require";
        }
    };
    private static final Function<FiguraLuaRuntime, LuaValue> LOADSTRING_FUNC = runtime -> new VarArgFunction() {
        @Override
        public Varargs invoke(Varargs args) {
            try {
                return runtime.userGlobals.load(args.arg(1).checkjstring(), "loadstring", runtime.userGlobals);
            } catch (LuaError e) {
                return varargsOf(NIL, e.getMessageObject());
            }
        }

        @Override
        public String tojstring() {
            return "function: loadstring";
        }
    };
    private void loadExtraLibraries() {
        //require
        userGlobals.set("require", requireFunction);

        //load print functions
        FiguraLuaPrinter.loadPrintFunctions(this);

        //custom loadstring
        LuaValue loadstring = LOADSTRING_FUNC.apply(this);
        this.setGlobal("load", loadstring);
        this.setGlobal("loadstring", loadstring);

        //load math library
        try (InputStream inputStream = FiguraMod.class.getResourceAsStream("/assets/" + FiguraMod.MOD_ID + "/scripts/math.lua")) {
            if (inputStream == null) throw new IOException("Unable to get resource");
            userGlobals.load(new String(inputStream.readAllBytes()), "math").call();
        } catch (Exception e) {
            error(new LuaError("Failed to load builtin math script:\n" + e.getMessage()));
        }

        //Change the type() function
        setGlobal("type", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                if (arg.type() == LuaValue.TUSERDATA)
                    return LuaString.valueOf(typeManager.getTypeName(arg.checkuserdata().getClass()));
                if (arg.type() == LuaValue.TTABLE && arg.getmetatable() != null) {
                    LuaValue __type = arg.getmetatable().rawget("__type");
                    if (!__type.isnil())
                        return __type;
                }
                return LuaString.valueOf(arg.typename());
            }
        });
    }

    private final Function<String, LuaValue> INIT_SCRIPT = name -> {
        //format name
        if (name.endsWith(".lua"))
            name = name.substring(0, name.length() - 4);

        //already loaded
        LuaValue val = loadedScripts.get(name);
        if (val != null)
            return val;

        //not found
        String src = scripts.get(name);
        if (src == null)
            throw new LuaError("Tried to require nonexistent script \"" + name + "\"!");

        //load
        LuaValue value = userGlobals.load(src, name).call(name);
        if (value == LuaValue.NIL)
            value = LuaValue.TRUE;

        //cache and return
        loadedScripts.put(name, value);
        return value;
    };
    public boolean init(ListTag autoScripts) {
        if (scripts.size() == 0)
            return false;

        try {
            if (autoScripts == null) {
                for (String name : scripts.keySet())
                    INIT_SCRIPT.apply(name);
            } else {
                for (Tag name : autoScripts)
                    INIT_SCRIPT.apply(name.getAsString());
            }
        } catch (LuaError e) {
            error(e);
            return false;
        }

        return true;
    }

    public void error(Exception e) {
        LuaError err = e instanceof LuaError lua ? lua : new LuaError(e.getMessage());
        FiguraLuaPrinter.sendLuaError(err, owner.entityName, owner.owner);
        owner.scriptError = true;
        owner.luaRuntime = null;
    }

    private final ZeroArgFunction onReachedLimit = new ZeroArgFunction() {
        @Override
        public LuaValue call() {
            LuaError error = new LuaError("Script overran resource limits!");
            setInstructionLimit(1);
            throw error;
        }
    };
    public void setInstructionLimit(int limit) {
        userGlobals.running.state.bytecodes = 0;
        setHookFunction.invoke(LuaValue.varargsOf(onReachedLimit, LuaValue.EMPTYSTRING, LuaValue.valueOf(Math.max(limit, 1))));
    }

    public int getInstructions() {
        return userGlobals.running.state.bytecodes;
    }
}
