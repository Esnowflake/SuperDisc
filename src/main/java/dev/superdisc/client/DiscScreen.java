package dev.superdisc.client;

import dev.superdisc.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

public final class DiscScreen extends Screen {
    public final String key;
    private EditBox path;
    private Button pick, play, pause, restart, mode, notify;
    private Slider progress, volume;
    private int left,top,w;
    private boolean choosing;
    private String lastPath="";
    private double displayedTime;
    public DiscScreen(String key){super(Component.literal("超级唱片 · Super Disc"));this.key=key;}
    private Component text(String s){return Component.literal(s);}
    private Button button(String title,int x,int y,int width,Runnable action){return addRenderableWidget(Button.builder(text(title),b->action.run()).bounds(x,y,width,20).build());}
    @Override protected void init(){
        w=Math.min(380,width-24);left=(width-w)/2;top=Math.max(8,(height-238)/2);
        path=new EditBox(font,left+10,top+42,w-100,20,text("音频文件路径"));path.setMaxLength(4096);path.setValue(ClientPlayback.path(key));lastPath=path.getValue();addRenderableWidget(path);
        path.setResponder(value->{if(value.isBlank()&&!ClientPlayback.path(key).isEmpty()){lastPath="";ClientPlayback.clear(key);}});
        pick=button("选择文件",left+w-84,top+42,74,this::choose);
        int cell=(w-26)/4;
        play=button("开始播放",left+10,top+75,cell,()->{
            var l=ClientPlayback.get(key);if(l==null)return;String value=path.getValue().trim();
            if(value.isEmpty()){ClientPlayback.clear(key);return;}
            if(l.track.hash.isEmpty()||!value.equals(ClientPlayback.path(key))){try{ClientPlayback.importFile(key,Path.of(value),true);}catch(Exception e){ClientPlayback.notice("路径无效");}}
            else ClientPlayback.command(key,"play",0);
        });
        pause=button("暂停播放",left+12+cell,top+75,cell,()->ClientPlayback.command(key,"pause",0));
        restart=button("重新播放",left+14+cell*2,top+75,cell,()->ClientPlayback.command(key,"restart",0));
        mode=button("播完暂停",left+16+cell*3,top+75,cell,()->ClientPlayback.command(key,"mode",0));
        progress=addRenderableWidget(new Slider(left+10,top+113,w-20,0,"进度",v->{var l=ClientPlayback.get(key);if(l!=null)ClientPlayback.command(key,"seek",v*l.track.duration);}));
        volume=addRenderableWidget(new Slider(left+10,top+151,w-20,.5,"音量",v->ClientPlayback.command(key,"volume",v*2)));
        notify=button("同步通知：开",left+10,top+183,125,()->ClientPlayback.setNotifications(!ClientPlayback.notifications));
        button("解除绑定",left+140,top+183,85,()->{ClientPlayback.command(key,"unbind",0);onClose();});
        button("关闭",left+w-75,top+183,65,this::onClose);
    }
    private void choose(){
        if(choosing)return;choosing=true;pick.active=false;
        // Native dialog runs separately, so decoding and multiplayer audio keep running.
        CompletableFuture.supplyAsync(()->{
            try(MemoryStack stack=MemoryStack.stackPush()){
                var filters=stack.mallocPointer(2);filters.put(stack.UTF8("*.mp3")).put(stack.UTF8("*.ogg")).flip();
                return TinyFileDialogs.tinyfd_openFileDialog("Super Disc - MP3 / Ogg Vorbis", "", filters,"Audio (*.mp3, *.ogg)",false);
            }
        }).whenComplete((selected,error)->minecraft.execute(()->{
            choosing=false;if(pick!=null)pick.active=true;
            if(minecraft.screen!=this)return;
            if(error!=null){ClientPlayback.notice("无法打开文件选择器，可直接输入完整路径。");SuperDisc.LOG.warn("File dialog",error);return;}
            if(selected==null||selected.isBlank()){
                path.setValue("");lastPath="";ClientPlayback.clear(key);
            }else{
                path.setValue(selected);lastPath=selected;ClientPlayback.importFile(key,Path.of(selected),false);
            }
        }));
    }
    @Override public void onFilesDrop(List<Path> files){if(files.size()==1){path.setValue(files.get(0).toString());lastPath=path.getValue();ClientPlayback.importFile(key,files.get(0),false);}}
    @Override public void tick(){
        path.tick();var l=ClientPlayback.get(key);if(l==null){onClose();return;}
        Track t=l.track;boolean owner=minecraft.player!=null&&t.owner.equals(minecraft.player.getUUID());
        String current=ClientPlayback.path(key);if(!path.isFocused()&&!current.equals(lastPath)){path.setValue(current);lastPath=current;}
        mode.setMessage(text(t.loop?"模式：循环":"播完暂停"));notify.setMessage(text("同步通知："+(ClientPlayback.notifications?"开":"关")));
        displayedTime=ClientPlayback.displayedPosition(l);
        progress.active=owner&&t.duration>0&&!t.syncing;progress.update(t.duration>0?displayedTime/t.duration:0);
        volume.update(t.volume/2);
        boolean busy=choosing||ClientPlayback.importing(key)||l.preparing||l.uploadOffset>=0;
        pick.active=!choosing;play.active=!busy;pause.active=t.playing||t.syncing;restart.active=!busy&&!t.hash.isEmpty();
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
        renderBackground(g);g.fill(left,top,left+w,top+238,0xF0202529);g.fill(left,top,left+w,top+2,0xFFFFCC44);
        g.drawCenteredString(font,title,left+w/2,top+10,0xFFFFCC44);g.drawString(font,"音频文件路径（MP3 / Ogg Vorbis）",left+10,top+29,0xFFE0E0E0);
        var l=ClientPlayback.get(key);if(l!=null){
            Track t=l.track;boolean owner=minecraft.player!=null&&t.owner.equals(minecraft.player.getUUID());
            g.drawString(font,format(displayedTime)+" / "+format(t.duration)+(owner?"  · 可调整进度":"  · 仅导入者可调整进度"),left+10,top+100,0xFFCCCCCC);
            g.drawString(font,"音量 0–200% · 100% 原音量 · 峰值保护",left+10,top+138,0xFFCCCCCC);
            String status=t.syncing?"等待所有收听者同步完成":t.playing?"正在播放":ClientPlayback.importing(key)?"正在校验文件并自动同步…":l.status;
            g.drawString(font,font.plainSubstrByWidth(status,w-20),left+10,top+214,0xFF88DDCC);
        }
        super.render(g,mouseX,mouseY,partial);
    }
    private static String format(double seconds){int s=Math.max(0,(int)seconds);return String.format("%02d:%02d",s/60,s%60);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){Net.toServer("close",new net.minecraft.nbt.CompoundTag());}
    private static final class Slider extends AbstractSliderButton {
        final String label;final DoubleConsumer commit;boolean dragging;
        Slider(int x,int y,int width,double value,String label,DoubleConsumer commit){super(x,y,width,20,Component.empty(),value);this.label=label;this.commit=commit;updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(label+"："+(label.equals("音量")?Math.round(value*200):(int)Math.floor(value*100))+"%"));}
        @Override protected void applyValue(){} // send at release, not every rendered frame
        @Override public void onClick(double x,double y){dragging=true;super.onClick(x,y);}
        @Override public void onRelease(double x,double y){super.onRelease(x,y);dragging=false;commit.accept(value);}
        @Override public boolean keyPressed(int key,int scan,int mods){boolean result=super.keyPressed(key,scan,mods);if(result)commit.accept(value);return result;}
        void update(double v){if(!dragging){value=Math.max(0,Math.min(1,v));updateMessage();}}
    }
}
