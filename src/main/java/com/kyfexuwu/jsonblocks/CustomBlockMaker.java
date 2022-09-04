package com.kyfexuwu.jsonblocks;

import com.google.gson.JsonObject;
import com.kyfexuwu.jsonblocks.lua.CustomScript;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.*;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.LuaValue;

import java.util.LinkedList;
import java.util.function.Function;

import static com.kyfexuwu.jsonblocks.Utils.validName;

public class CustomBlockMaker {
    public static JsonObject tempBlockStates;//prolly can be fixed

    public static Block from(FabricBlockSettings settings, JsonObject blockStates, CustomScript scriptContainer) {
        tempBlockStates = blockStates;//PLEASE ONLY USE ME IN THE STATIC BLOCK I DO NOT KNOW WHAT WILL HAPPEN IF U DONT

        class customBlock extends Block {

            public static final LinkedList<Property> propsList = new LinkedList<>();
            public static final Property[] props;

            static {
                for (String propName : tempBlockStates.keySet()) {
                    if (!validName.matcher(propName).matches()) continue;
                    var thisState = tempBlockStates.get(propName).getAsJsonObject();
                    switch (thisState.get("type").getAsString()) {
                        case "int":
                            propsList.add(IntProperty.of(
                                    propName,
                                    thisState.get("min").getAsInt(),
                                    thisState.get("max").getAsInt()
                            ));
                            break;
                        case "boolean":
                            propsList.add(BooleanProperty.of(propName));
                            break;
                        case "enum":
                            //propsList.add(IntProperty.of("test", 0, 3));
                            break;
                        case "direction":
                            propsList.add(DirectionProperty.of(propName));
                            break;
                    }
                }
                props = propsList.toArray(new Property[0]);
                propsList.clear();
            }

            public customBlock(Settings settings) {
                super(settings);

                var defaultState = getStateManager().getDefaultState();
                for (Property prop : props) {
                    try {
                        var jsonDefault = blockStates.get(prop.getName()).getAsJsonObject().get("default");
                        switch (prop.getType().getName()) {
                            case "java.lang.Integer" -> defaultState = defaultState.with(prop, jsonDefault.getAsInt());
                            case "java.lang.Boolean" -> defaultState = defaultState.with(prop, jsonDefault.getAsBoolean());
                            case "net.minecraft.util.math.Direction" -> defaultState = defaultState.with(prop,
                                    Direction.byName(jsonDefault.getAsString()));
                        }
                    } catch (Exception e) {
                        System.out.println("Property " + prop.getName() + " has an invalid default value");
                    }
                }
                setDefaultState(defaultState);
            }

            @Override
            protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
                for (Property prop : props) {
                    builder.add(prop);
                }
            }

            //--
            // add overrides here!

            @Override
            public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
                tryAndExecute(scriptContainer,"randomTick",new Object[]{state,world,pos,random});
            }

            @Override
            public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
                tryAndExecute(scriptContainer,"scheduledTick",new Object[]{state,world,pos,random});
            }

            @Override
            public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
                return tryAndExecute(scriptContainer,"onUse",new Object[]{state,world,pos,player,hand,hit},returnValue->{
                    try {
                        return ActionResult.valueOf(returnValue.toString());
                    }catch(IllegalArgumentException e) {
                        return ActionResult.PASS;
                    }
                });
            }

            @Override
            public void onBroken(WorldAccess world, BlockPos pos, BlockState state){
                tryAndExecute(scriptContainer,"onBroken",new Object[]{world,pos,state});
            }

            @Override
            public void onSteppedOn(World world, BlockPos pos, BlockState state, Entity entity) {
                tryAndExecute(scriptContainer,"onSteppedOn",new Object[]{world,pos,state,entity});
            }

            @Override
            public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
                tryAndExecute(scriptContainer,"onPlaced",new Object[]{world,pos,state,placer,itemStack});
            }
        }

        return new customBlock(settings);
    }

    public static <T> T tryAndExecute(CustomScript scriptContainer, String funcString, Object[] args, Function<LuaValue,T> transformFunc){
        if(scriptContainer==null) return null;
        var func = scriptContainer.runEnv.get(funcString);
        if(func.isnil())
            return null;

        var luaArgs=new LuaValue[args.length];
        for(int i=0;i<luaArgs.length;i++){
            luaArgs[i]=Utils.toLuaValue(args[i]);
        }

        return transformFunc.apply(func.invoke(luaArgs).arg1());
    }
    public static void tryAndExecute(CustomScript scriptContainer, String funcString,Object[] args){
        tryAndExecute(scriptContainer, funcString, args, returnValue -> null);
        //calls tryAndExecute, but always returns null
    }
}
