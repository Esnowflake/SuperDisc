package dev.superdisc.client;

import dev.superdisc.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Only visible rows own widgets; network snapshots update existing sliders without rebuilding them. */
public final class VolumeScreen extends Screen {
    public final String key;
    private int left,top,w,h,offset,capacity;
    private boolean returning;
    private List<UUID> identities=List.of();
    private final Map<UUID,PlayerSlider> sliders=new LinkedHashMap<>();
    public VolumeScreen(String key){super(Component.literal("多人音量管理"));this.key=key;}
    @Override protected void init(){
        w=Math.min(440,width-20);h=Math.min(310,height-12);left=(width-w)/2;top=(height-h)/2;
        capacity=Math.max(1,(h-104)/48);rebuild();ClientPlayback.watchVolumes(key,true);
    }
    private void rebuild(){
        clearWidgets();sliders.clear();var list=ClientPlayback.listeners(key);
        offset=Math.max(0,Math.min(offset,Math.max(0,list.size()-capacity)));
        identities=list.stream().map(ClientPlayback.Listener::id).toList();
        for(int i=offset;i<Math.min(list.size(),offset+capacity);i++){
            var entry=list.get(i);int y=top+65+(i-offset)*48;
            var slider=new PlayerSlider(entry.id(),left+55,y+17,w-76,entry.volume());sliders.put(entry.id(),slider);addRenderableWidget(slider);
        }
        addRenderableWidget(Button.builder(Component.literal("返回唱片机"),b->onClose()).bounds(left+12,top+h-29,105,20).build());
        updateSliders();
    }
    private void updateSliders(){
        for(var entry:ClientPlayback.listeners(key)){
            var slider=sliders.get(entry.id());if(slider==null)continue;
            boolean self=minecraft.player!=null&&entry.id().equals(minecraft.player.getUUID());
            slider.active=ClientPlayback.canManage(key)&&(!entry.locked()||self);
            if(!slider.active)slider.dragging=false;
            if(!slider.dragging){slider.valueFromServer(entry.volume());}
        }
    }
    @Override public void tick(){
        if(ClientPlayback.get(key)==null){minecraft.setScreen(null);return;}
        if(!ClientPlayback.canManage(key)){onClose();return;}
        var next=ClientPlayback.listeners(key).stream().map(ClientPlayback.Listener::id).toList();
        if(!next.equals(identities))rebuild();else updateSliders();
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double delta){
        if(x>=left&&x<=left+w&&y>=top+59&&y<top+h-35){
            if(sliders.values().stream().anyMatch(s->s.dragging))return true;
            int next=Math.max(0,Math.min(Math.max(0,identities.size()-capacity),offset-(int)Math.signum(delta)));
            if(next!=offset){offset=next;rebuild();}return true;
        }return super.mouseScrolled(x,y,horizontal,delta);
    }
    // Screen.render draws this background once, then the interactive widgets.
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float partial){
        renderTransparentBackground(g);g.fill(left,top,left+w,top+h,0xF0182028);g.fill(left,top,left+w,top+3,0xFFFFCB57);
        g.drawString(font,title,left+13,top+13,0xFFFFDC82);
        g.drawString(font,"为每位玩家单独设置这台唱片机的音量",left+13,top+31,0xFFB9C9D8);
        g.drawString(font,"保护已开启的玩家仅可自行调整",left+13,top+45,0xFF8195A8);
        var list=ClientPlayback.listeners(key);
        for(int i=offset;i<Math.min(list.size(),offset+capacity);i++){
            var entry=list.get(i);int y=top+61+(i-offset)*48;
            g.fill(left+10,y,left+w-10,y+44,0xFF283542);
            var info=minecraft.getConnection()==null?null:minecraft.getConnection().getPlayerInfo(entry.id());
            PlayerFaceRenderer.draw(g,info==null?DefaultPlayerSkin.get(entry.id()):info.getSkin(),left+17,y+10,28);
            g.drawString(font,font.plainSubstrByWidth(entry.name(),Math.max(40,w-195)),left+55,y+5,0xFFF2F5FA);
            String badge=entry.locked()?"已保护":"允许调整";
            g.drawString(font,badge,left+w-18-font.width(badge),y+5,entry.locked()?0xFF94A5B5:0xFF83DCC0);
        }
        if(list.isEmpty())g.drawCenteredString(font,"正在读取在线玩家…",left+w/2,top+92,0xFFB9C9D8);
        if(list.size()>capacity){
            int barTop=top+62,barHeight=capacity*48-4,thumb=Math.max(10,barHeight*capacity/list.size());
            int y=barTop+(barHeight-thumb)*offset/(list.size()-capacity);
            g.fill(left+w-6,barTop,left+w-3,barTop+barHeight,0xFF344454);g.fill(left+w-6,y,left+w-3,y+thumb,0xFFFFCB57);
        }
        String footer=list.size()+" 位在线玩家 · 滚轮翻动";
        g.drawString(font,footer,left+w-12-font.width(footer),top+h-22,0xFF98ADBE);
    }
    @Override public void onClose(){returning=true;ClientPlayback.watchVolumes(key,false);minecraft.setScreen(new DiscScreen(key));}
    @Override public void removed(){if(!returning){ClientPlayback.watchVolumes(key,false);Net.toServer("close",new net.minecraft.nbt.CompoundTag());}}
    @Override public boolean isPauseScreen(){return false;}
    private final class PlayerSlider extends AbstractSliderButton {
        final UUID player;boolean dragging;
        PlayerSlider(UUID player,int x,int y,int width,float volume){super(x,y,width,18,Component.empty(),volume/4);this.player=player;updateMessage();}
        void valueFromServer(float volume){value=volume/4;updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(Math.round(value*400)+"%"));}
        @Override protected void applyValue(){}
        private void send(){if(active)ClientPlayback.setVolume(key,player,value*4);}
        @Override public void onClick(double x,double y){dragging=true;super.onClick(x,y);}
        @Override public void onRelease(double x,double y){super.onRelease(x,y);dragging=false;send();}
        @Override public boolean keyPressed(int key,int scan,int mods){boolean changed=super.keyPressed(key,scan,mods);if(changed)send();return changed;}
    }
}
