package com.kyfexuwu.m3we.lua;

import com.kyfexuwu.m3we.m3we;
import com.kyfexuwu.m3we.Utils;
import com.kyfexuwu.m3we.lua.api.*;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.luaj.vm2.LuaValue.NIL;

public class CustomScript {

    public static CustomScript NULL = new CustomScript(null, true);

    public Globals runEnv;
    public final String name;
    public final boolean isFake;

    static final Disabled disabled = new Disabled();


    public static final String contextIdentifier = "__context";
    public final JavaExclusiveTable contextObj = new JavaExclusiveTable();
    public static final ArrayList<String> apiNames = new ArrayList<>();
    private static class CustomGlobals extends Globals{
        @Override
        public void hashset(LuaValue luaKey, LuaValue val) {
            try{
                var key=luaKey.checkjstring();
                if(key.equals(contextIdentifier))
                    throw new LuaError("Cannot overwrite "+contextIdentifier);
                for(var name : apiNames){
                    if(key.equals(name)) throw new LuaError("Cannot overwrite API "+name);
                }
            }catch(Exception ignored){}
            super.hashset(luaKey, val);
        }
    }
    private static final Logger printLogger = Logger.getLogger("m3we-consoleprint");
    private static Globals unsafeGlobal(){
        var toReturn = new CustomGlobals();
        toReturn.load(new JseBaseLib());
        toReturn.load(new PackageLib());//needed, trust me
        toReturn.load(new TableLib());
        toReturn.load(new StringLib());
        toReturn.load(new JseMathLib());

        toReturn.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return print(toReturn.get(contextIdentifier).get("env").optjstring("none"), args);
            }
        });
        toReturn.set("consoleprint", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var toPrint = new StringBuilder();
                for(int i=0;i<args.narg()-1;i++) toPrint.append(args.arg(i+1)).append(", ");
                printLogger.info(toPrint.append(args.arg(args.narg())).toString());
                return NONE;
            }
        });
        toReturn.set("explore", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                return explore(value);
            }
        });
        toReturn.set("loadclass", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                try {
                    return loadclass(value.checkjstring());
                }catch(LuaError e){
                    return NIL;
                }
            }
        });

        toReturn.load(new BlockEntityAPI());
        toReturn.load(new CreateAPI());
        toReturn.load(new DatastoreAPI());
        toReturn.load(new EnumsAPI());
        toReturn.load(new GuiAPI());
        toReturn.load(new MiscAPI());
        toReturn.load(new PropertyAPI());
        toReturn.load(new RedstoneAPI());
        toReturn.load(new RegistryAPI());
        toReturn.load(new SignalsAPI());

        return toReturn;
    }
    protected static Globals safeGlobal(){
        var toReturn = unsafeGlobal();

        var load = toReturn.get("load");
        toReturn.set("loadLib", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                if(!arg.isstring() || arg.checkjstring().contains("..")) return NIL;
                try{
                    return load.call(Files.readString(new File(m3we.scriptsFolder.getAbsolutePath() +
                            "\\" + arg.checkjstring() + ".lua").toPath())).call();
                }catch(Exception ignored){}
                return NIL;
            }
        });
        toReturn.set("require",disabled);
        toReturn.set("load",disabled);
        toReturn.set("dofile",disabled);
        toReturn.set("loadfile",disabled);

        return toReturn;
    }

    public static Varargs createVarArgs(Object... args){
        var luaArgs = Arrays.stream(args).map(Utils::toLuaValue).toArray(LuaValue[]::new);
        return new Varargs() {
            @Override
            public LuaValue arg(int i) {
                return luaArgs[i-1];
            }

            @Override
            public int narg() {
                return luaArgs.length;
            }

            @Override
            public LuaValue arg1() {
                return arg(1);
            }

            @Override
            public Varargs subargs(int start) {
                return createVarArgs(Arrays.copyOfRange(luaArgs,start-1,luaArgs.length));
            }
        };
    }

    static class Disabled extends VarArgFunction{
        @Override
        public Varargs invoke(Varargs args) {
            print("client", LuaValue.valueOf("This value is disabled"));
            return NIL;
        }
    }
    public static MinecraftServer currentServer;
    //can we autodetect environment?
    public static Varargs print(String env, Varargs args){
        StringBuilder toPrint= new StringBuilder();
        if(args.narg()==1 && args.arg(1).isstring()){
            toPrint.append(args.arg(1).checkjstring());
        }else {
            for (int i = 1, length = args.narg(); i <= length; i++) {
                toPrint.append(i > 1 ? ", " : "").append(valueToString(args.arg(i), 0));
            }
        }
        try {//CHANGE
            var message = Text.of(toPrint.toString().replace("\r",""));
            if (env.equals("server")) {
                for (var player : currentServer.getPlayerManager().getPlayerList()) {
                    if (player.hasPermissionLevel(1)) player.sendMessage(message);
                }
            } else {
                MinecraftClient.getInstance().player.sendMessage(message);
            }
        }catch(Exception e){
            m3we.LOGGER.debug("m3we print: "+toPrint);
        }
        return NIL;
    }
    public static void print(String env, Object... args){
        print(env, createVarArgs(args));
    }
    public static LuaValue explore(LuaValue value){
        MutableText message = Text.literal(Utils.deobfuscate(Utils.toObject(value).getClass().getSimpleName())+": ");

        try {
            if (value.typename().equals("surfaceObj") || value.typename().equals("table")) {
                LuaValue nextKey = (LuaValue) value.next(NIL);
                //int maxProps=100;
                do {
                    LuaValue finalNextKey = nextKey;
                    message.append(Text.literal(nextKey.toString() + ", ")
                            .setStyle(Style.EMPTY.withClickEvent(new CustomClickEvent(() -> {
                                explore(value.get(finalNextKey));
                            })).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Text.literal(valueToString(value.get(finalNextKey), 0))))));

                    nextKey = (LuaValue) value.next(nextKey);
                    //maxProps--;
                } while (nextKey != NIL /* &&maxProps>0 */);
            } else {
                message.append(Text.literal(value.toString()));
            }
        }catch(Exception e){ e.printStackTrace(); }

        var chatHud = MinecraftClient.getInstance().inGameHud.getChatHud();
        chatHud.addMessage(message);
        return NIL;
    }
    public static LuaValue loadclass(String string){
        Class<?> toReturn;
        try {
            var classToken=Arrays.stream(Translations.classesTranslations)
                    .filter(token->token!=null&&token.longDeobfuscated.equals(string)).findFirst();
            toReturn=Class.forName(classToken.isPresent()?
                    (Translations.OBFUSCATED?classToken.get().longObfuscated:classToken.get().longDeobfuscated):
                    string);
        }catch(Exception e) {
            e.printStackTrace();
            return NIL;
        }
        return Utils.toLuaValue(toReturn);
    }

    protected CustomScript(String name, boolean isFake){
        this.name=name;
        this.isFake=isFake;
    }
    public CustomScript(String fileName){
        if(fileName==null) {
            this.name = "fake";
            this.isFake = true;
            return;
        }

        this.name=fileName;
        this.isFake=false;

        this.setScript(fileName);

        scripts.add(this);
    }
    public final List<Consumer<CustomScript>> updateListeners = new ArrayList<>();
    private void setScript(String fileName){
        this.runEnv = safeGlobal();
        this.runEnv.set(contextIdentifier, this.contextObj);

        LoadState.install(this.runEnv);
        LuaC.install(this.runEnv);
        try {
            this.runEnv.load(
                Files.readString(new File(m3we.m3weFolder + "\\scripts\\" + fileName + ".lua").toPath())
            ).call();
            for(var listener : this.updateListeners) listener.accept(this);
        }catch(IOException | LuaError e){
            m3we.LOGGER.error("script "+fileName+" not loaded... it was a "+e.getClass().getName()+" exception");
            //e.printStackTrace();
        }
    }
    public void remove(){
        if(this.isFake) return;

        for(int i=0;i<scripts.size();i++){
            if(scripts.get(i).name.equals(this.name)) {
                scripts.remove(i);
                i--;
            }
        }
    }
    public void setStateWorldPos(BlockState state, World world, BlockPos pos){
        if(this.isFake) return;

        this.contextObj.javaSet("blockState",Utils.toLuaValue(state));//should change these keys to constants
        this.contextObj.javaSet("world",Utils.toLuaValue(world));
        this.contextObj.javaSet("blockPos",Utils.toLuaValue(pos));
    }
    public void clearStateWorldPos() {
        this.setStateWorldPos(null, null, null);
    }

    public static final ArrayList<CustomScript> scripts = new ArrayList<>();
    public static void reloadScript(String name){
        for(CustomScript script : scripts){
            if(!(script.name+".lua").equals(name))
                continue;

            script.setScript(script.name);
        }
    }

    private static final int maxLevels=5;
    private static String valueToString(LuaValue value, int indents){
        StringBuilder toReturn= new StringBuilder();
        toReturn.append("  ".repeat(indents));

        switch (value.typename()) {
            case "nil", "boolean", "number", "function", "userdata", "thread" -> toReturn.append(value);
            case "string" -> toReturn.append("\"").append(value).append("\"");
            case "table" -> {
                if(indents<maxLevels) {
                    toReturn.append("{\n");
                    var keys = ((LuaTable)value).keys();
                    for(LuaValue key : keys){
                        toReturn.append(key).append("=").append(valueToString(value.get(key), indents + 1)).append(",\n");
                    }
                    toReturn.append("  ".repeat(indents)).append("}");
                }else{
                    toReturn.append("{...}");
                }
            }
            case "surfaceObj" -> toReturn.append("java object: ").append(Utils.deobfuscate(
                    ((LuaSurfaceObj) value).object.getClass().getSimpleName()));
            case "undecidedFunc" -> {
                var refMethods = ((UndecidedLuaFunction) value).methods;
                toReturn.append("java function: ")
                        .append(Utils.deobfuscate(refMethods[0].getName()));

                for(Executable m : refMethods) {
                    var token = m instanceof Method?
                        Translations.getToken((Method)m):
                        new Translations.MethodToken("<init>","<init>","type",
                                new String[]{"p1","p2","p3"});

                    if (token.paramNames.length > 0) {
                        toReturn.append("\n • [takes parameters: ");
                        for (int i=0;i<token.paramNames.length;i++) {
                            if(i>0) toReturn.append(", ");
                            toReturn.append(token.paramNames[i])
                                    .append(" (")
                                    .append(token.paramClasses[i])
                                    .append(")");
                        }
                    } else {
                        toReturn.append(" [takes no parameters,");
                    }
                    toReturn.append(" and ");

                    if(m instanceof Method method) {
                        var returnClass = method.getReturnType();
                        if (!returnClass.equals(Void.class)) {
                            toReturn.append("returns with type ")
                                    .append(Utils.deobfuscate(returnClass.getSimpleName()))
                                    .append("]");
                        } else {
                            toReturn.append("does not return a value]");
                        }
                    }else{
                        toReturn.append("and creates a new ")
                                .append(((Constructor<?>)m).getDeclaringClass().getSimpleName())
                                .append("]");
                    }
                }
            }
        }
        return toReturn.toString();
    }

    public static LuaValue finalizeAPI(String name, LuaValue api, LuaValue env){
        apiNames.add(name);

        env.set(name, api);
        env.get("package").get("loaded").set(name, api);
        return api;
    }
}
