package com.flowify.ettea.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Headless Spotify web player on a plain system WebView. The Spotify-side bridge, the warm
 * receiver and the settings UI all reach it through the com.flowify.ettea.player.* broadcast
 * actions below plus the warm-reply codes.
 *
 * <p>Why WebView rather than GeckoView, which an earlier revision used: Chromium never
 * preflights the User-Agent header, so the Widevine license and image fetches that died
 * under Gecko's custom-UA CORS
 * preflight (bmo#1629921) just work; the engine is a fraction of the memory; and
 * WebViewClient.shouldInterceptRequest gives real adblocking, which GeckoView cannot
 * do without a separate extension process. Desktop disguise (which is what makes
 * Spotify serve the full player) lives in {@link BrowserDisguise}.
 */
public final class WebPlayerService extends Service {
    private static final String TAG = "[SpicyWeb]";
    private static final String CHANNEL_ID = "spicy_player";
    private static final int NOTIFICATION_ID = 2001;
    private static final String HOME = "https://open.spotify.com/";
    /** Where the idle player parks: the settings page is the lightest route that still mounts
     *  the full player (bar, Connect device, dealer socket) - the home feed's shelves cost
     *  ~50 MB more renderer memory for nothing anyone sees. */
    private static final String PLAYER_HOME = "https://open.spotify.com/preferences";

    // The wire protocol. PlayerWarmReceiver and ConnectEntry send these verbatim, so any
    // rename has to land in all three files at once.
    public static final String ACTION_PLAY = "com.flowify.ettea.player.PLAY";
    public static final String ACTION_PAUSE = "com.flowify.ettea.player.PAUSE";
    public static final String ACTION_TOGGLE = "com.flowify.ettea.player.TOGGLE";
    public static final String ACTION_NEXT = "com.flowify.ettea.player.NEXT";
    public static final String ACTION_PREVIOUS = "com.flowify.ettea.player.PREVIOUS";
    public static final String ACTION_SEEK = "com.flowify.ettea.player.SEEK";
    public static final String ACTION_VOLUME = "com.flowify.ettea.player.VOLUME";
    public static final String ACTION_OPEN = "com.flowify.ettea.player.OPEN";
    public static final String ACTION_SEARCH = "com.flowify.ettea.player.SEARCH";
    public static final String ACTION_WARMUP = "com.flowify.ettea.player.WARMUP";
    public static final String ACTION_LOGIN = "com.flowify.ettea.player.LOGIN";
    public static final String ACTION_STOP = "com.flowify.ettea.player.STOP";
    public static final String ACTION_RECONNECT = "com.flowify.ettea.player.RECONNECT";
    /** Sent by WebLoginActivity once a sign-in completed: the parked player page is still the
     *  anonymous one it loaded before, so it has to reload into the new session. */
    static final String ACTION_LOGIN_DONE = "com.flowify.ettea.player.LOGIN_DONE";
    public static final String EXTRA_WARM_REPLY = "warm_reply";
    /** READY replies carry what is known of the device: its Connect id, the account's active
     *  device and whether audio is playing here. */
    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_ACTIVE_DEVICE_ID = "active_device_id";
    public static final String EXTRA_PLAYING = "playing";
    /** Set by callers about to hand playback over: hold READY until the device id is known. */
    public static final String EXTRA_NEED_DEVICE_ID = "need_device_id";
    /** WARMUP extra: whether to recover the Connect session after a network change. */
    public static final String EXTRA_NETWORK_RECOVERY = "network_recovery";
    /** Reply code sent later over a stored WARMUP reply: the player came back from a network
     *  change as a new device while music was playing - Spotify should select it again. */
    public static final int EVENT_RESELECT = 6;
    /** Reply code sent over the stored WARMUP reply: playback is here with shuffle on - Spotify
     *  turns it off through its own media session (its web player's button can't on Free). */
    public static final int EVENT_SHUFFLE_OFF = 7;

    /** The sign-in screen signed the web session out ("sign in with another account"). */
    static final String ACTION_SIGNED_OUT = "com.flowify.ettea.player.SIGNED_OUT";
    public static final int WARM_RESULT_READY = 1;
    public static final int WARM_RESULT_STARTING = 2;
    public static final int WARM_RESULT_FAILED = 3;
    public static final int WARM_RESULT_LOGIN_REQUIRED = 4;
    /** Background start refused; the reply carries {@link #EXTRA_KICK}, a PendingIntent for the
     *  same start that the (foreground) sender fires with its own start privilege. */
    public static final int WARM_RESULT_NEEDS_KICK = 5;
    public static final String EXTRA_KICK = "kick";

    /**
     * The player page is a 1x1 overlay nobody ever sees, but the desktop SPA still renders and
     * animates as if it were on a monitor. This keeps only what playback needs:
     * <ul>
     *   <li>rAF callbacks run once a second (like a background tab) instead of every
     *       vsync - the progress bar and marquees were the main renderer wake-ups;</li>
     *   <li>CSS animations/transitions finish instantly (transitionend still fires);</li>
     *   <li>everything except the now-playing bar is display:none - no layout, no paint, and
     *       virtualised lists mount almost nothing. The bar's controls stay in the DOM, and
     *       element.click() works on hidden nodes, so every command still reaches them.</li>
     * </ul>
     * Nothing here touches audio, timers or network.
     */
    private static final String IDLE_RENDER_JS = "(function(){try{"
            + "if(window.__spicyIdle)return;window.__spicyIdle=true;"
            + "var q=new Map(),n=0,t=0;"
            + "function flush(){t=0;var c=q;q=new Map();var now=performance.now();"
            + "c.forEach(function(f){try{f(now);}catch(e){}});}"
            + "window.requestAnimationFrame=function(f){var id=++n;q.set(id,f);"
            + "if(!t)t=setTimeout(flush,1000);return id;};"
            + "window.cancelAnimationFrame=function(id){q.delete(id);};"
            + "function css(){try{var s=document.createElement('style');"
            + "s.textContent='*,*::before,*::after{animation-duration:0s!important;animation-delay:0s!important;"
            + "animation-iteration-count:1!important;transition:none!important;scroll-behavior:auto!important}"
            + "img,video,canvas,picture,svg image{visibility:hidden!important}"
            + "#global-nav-bar,#Desktop_LeftSidebar_Id,nav,#main-view,#lyrics-cinema,"
            + "aside:not([data-testid=\"now-playing-bar\"]){display:none!important}';"
            + "(document.head||document.documentElement).appendChild(s);}catch(e){}}"
            + "if(document.documentElement)css();else document.addEventListener('DOMContentLoaded',css);"
            + "}catch(e){}})();";

    /**
     * Installed before any page script runs, on the player page only:
     * <ul>
     *   <li>learns this page load's own Connect device id (from its track-playback
     *       registration - the id changes on every load, and Spotify's hand-off selects exactly
     *       this id, so a user's real desktop browser can never be mistaken for us) and the
     *       account's active device (from the connect-state cluster the page gets on
     *       registration and over its dealer socket; a cheap substring scan, never a JSON parse
     *       of every message);</li>
     *   <li>registers the device as "Spicy Connect" instead of "Web Player (Chrome)", by
     *       rewriting the name in the page's own registration requests;</li>
     *   <li>drops the remote "log out" command (Spotify's device menu offers it for web
     *       players) before the player sees it - obeying it pauses, unregisters the device
     *       and signs the page out, which here means a dead Connect device until the next
     *       sign-in;</li>
     *   <li>keeps the dealer socket reachable ({@code __spicyDealer}) so network recovery can
     *       tell a live connection from a dead one;</li>
     *   <li>notes whether the latest cluster still lists this page's device
     *       ({@code __spicyListed}: null until known). Seen on-device: a page can drop out of the
     *       account's device list shortly after loading while its socket stays up - alive, but
     *       a device nobody can pick or hand playback to.</li>
     * </ul>
     */
    private static final String DEVICE_ID_JS = "(function(){try{"
            + "if(window.__spicyDev!==undefined)return;window.__spicyDev=null;window.__spicyAct=null;window.__spicyDealer=null;"
            + "window.__spicyListed=null;"
            + "function scan(t){try{if(typeof t!=='string')return;"
            // Every cluster lists the account's devices: is this page still one of them?
            + "var d=window.__spicyDev;if(d&&t.indexOf('\"devices\"')>=0&&(t.indexOf('\"active_device_id\"')>=0||t.indexOf('\"player_state\"')>=0))"
            + "window.__spicyListed=t.indexOf(d)>=0;"
            + "var i=t.indexOf('\"active_device_id\"');"
            + "if(i<0){if(t.indexOf('\"player_state\"')>=0&&t.indexOf('\"devices\"')>=0)window.__spicyAct=null;return;}"
            + "var m=/\"active_device_id\"\\s*:\\s*\"([0-9a-f]*)\"/.exec(t.substr(i,100));if(m){window.__spicyAct=m[1]||null;window.__spicyActAt=Date.now();}"
            // Another device took over: the next hand-off back here turns shuffle off again.
            + "if(window.__spicyAct!==window.__spicyDev)window.__spicyShufDone=false;}catch(e){}}"
            + "function isLogout(t){try{if(typeof t!=='string')return false;if(t.indexOf('log_out')>=0)return true;"
            + "if(t.indexOf('track-playback')<0)return false;var p=JSON.parse(t).payloads||[];"
            + "for(var i=0;i<p.length;i++){if(typeof p[i]==='string'){try{if(atob(p[i]).indexOf('log_out')>=0)return true;}catch(e){}}}"
            + "}catch(e){}return false;}"
            + "function drop(){window.__spicyLogoutBlocked=(window.__spicyLogoutBlocked||0)+1;"
            + "try{if(window.spicyPlayer)spicyPlayer.postMessage('logout-blocked');}catch(e){}}"
            + "function guard(h){return function(e){if(isLogout(e&&e.data)){drop();return;}return h.apply(this,arguments);};}"
            + "var of=window.fetch;"
            + "window.fetch=function(i,o){var u='';try{u=String(i&&i.url||i);"
            // The id shows in the track-playback registration and in the Connect state calls
            // (".../connect-state/v1/devices/hobs_<id>"); a page does not always make the first
            // soon, and a hand-off waiting on it timed out with the device unknown.
            + "var m=/track-playback.v1.devices.([0-9a-f]{40})/.exec(u)||/connect-state.v1.devices.hobs_([0-9a-f]{40})/.exec(u);if(m)window.__spicyDev=m[1];"
            + "if(o&&typeof o.body==='string'&&/track-playback.v1.devices|connect-state.v1.devices/.test(u)){"
            + "o.body=o.body.replace(/\"name\":\"Web Player \\([^\"]*\\)\"/g,'\"name\":\"Spicy Connect\"');}"
            + "}catch(e){}"
            + "var p=of.apply(this,arguments);"
            + "if(u.indexOf('/connect-state/v1/devices/')>0){p.then(function(r){return r.clone().text();}).then(scan,function(){});}"
            + "return p;};"
            + "var W=window.WebSocket;if(W){var N=function(a,b){var s=b===undefined?new W(a):new W(a,b);"
            + "if(String(a).indexOf('dealer')>=0){window.__spicyDealer=s;var ael=s.addEventListener;"
            + "s.addEventListener=function(t,h,o){if(t==='message'&&typeof h==='function')h=guard(h);return ael.call(this,t,h,o);};"
            + "var d=Object.getOwnPropertyDescriptor(W.prototype,'onmessage');"
            + "if(d&&d.set)Object.defineProperty(s,'onmessage',{configurable:true,get:function(){return d.get.call(this);},"
            + "set:function(h){d.set.call(this,typeof h==='function'?guard(h):h);}});"
            + "ael.call(s,'message',function(e){scan(e.data);});}return s;};"
            + "N.prototype=W.prototype;N.CONNECTING=0;N.OPEN=1;N.CLOSING=2;N.CLOSED=3;window.WebSocket=N;}"
            + "}catch(e){}})();";

    /** Reports real audio play/pause to the service so the wifi/wake locks follow playback
     *  that Spotify Connect started remotely (which never passes through dispatch()). The
     *  player's media element is never attached to the document, so listeners go on the
     *  element itself the first time it is played.
     *
     *  <p>Volume: the element always plays at full level, so the phone's media
     *  volume is the one and only control - the volume keys, the rocker and the
     *  per-app volume slider all move it, nothing else does. Spotify's own
     *  (Connect) volume for this device is deliberately left alone: it used to
     *  be followed onto the phone's media volume, which made the page's volume
     *  a second control on top of the phone's - a hand-off or a remote change
     *  slammed the phone to full or to muted, on top of whatever the phone was
     *  already at.
     *
     *  <p>Also here, because they need the same elements:
     *  <ul>
     *    <li>{@code __spicyHush(on)}: silences every media element during an ad (and any the
     *        player creates meanwhile), then fades them back in;</li>
     *    <li>a stall watchdog: an element that should be playing but hasn't moved for a while
     *        with nothing buffered gets play() again, then a same-position seek (which makes
     *        the player fetch again) and an "online" nudge - a weak network stutters instead
     *        of stopping for good;</li>
     *    <li>shuffle goes off the first time audio plays after this device became the active
     *        one (smart shuffle is a second "on" state, hence the bounded retries).</li>
     *  </ul> */
    private static final String MEDIA_STATE_JS = "(function(){try{"
            + "if(window.__spicyMedia||!window.HTMLMediaElement)return;window.__spicyMedia=true;"
            + "var last='';function post(s){if(s===last)return;last=s;try{if(window.spicyPlayer)spicyPlayer.postMessage(s);}catch(e){}}"
            + "var P=HTMLMediaElement.prototype,els=[],hush=false,fadeT=0;"
            + "var vd=Object.getOwnPropertyDescriptor(P,'volume');"
            + "function level(el){var v=el.__spicyVol;if(v===undefined)return el.__spicyPre!==undefined?el.__spicyPre:1;"
            + "return 1;}"
            + "if(vd&&vd.set)Object.defineProperty(P,'volume',{configurable:true,enumerable:vd.enumerable,"
            + "get:function(){return this.__spicyVol!==undefined?this.__spicyVol:vd.get.call(this);},"
            + "set:function(v){this.__spicyVol=v;vd.set.call(this,hush?0:level(this));}});"
            + "window.__spicyHush=function(on){if(!vd||!vd.set)return;on=!!on;if(on===hush)return;hush=on;clearInterval(fadeT);"
            + "if(on){els.forEach(function(e){try{if(e.__spicyVol===undefined)e.__spicyPre=vd.get.call(e);vd.set.call(e,0);}catch(x){}});return;}"
            + "var k=0;fadeT=setInterval(function(){k++;var f=Math.min(1,k/12);f=f*f*(3-2*f);"
            + "els.forEach(function(e){try{vd.set.call(e,level(e)*f);}catch(x){}});if(k>=12)clearInterval(fadeT);},60);};"
            // Shuffle off goes through Spotify itself ("shuffle-on" -> the service -> Spotify's own
            // media session), the same path as its shuffle button: the web player's button only
            // steps on to smart shuffle, which a Free account's server refuses. Whether shuffle
            // is on comes from the player's own state (AD_STATE_JS). The button - the control
            // right before "previous"; it has no test id and a localized label - is the fallback
            // when nothing changed, clicked again only once the state has moved on (it cycles
            // off, shuffle, smart shuffle).
            + "function shufBtn(){var b=document.querySelector('[data-testid=\"control-button-shuffle\"]');if(b)return b;"
            + "var s=document.querySelector('[data-testid=\"control-button-skip-back\"]');b=s&&s.previousElementSibling;"
            + "while(b&&b.tagName!=='BUTTON')b=b.querySelector('button')||b.previousElementSibling;return b;}"
            + "function shuffleOff(n,seq,k){try{var on=window.__spicyShuffleOn?window.__spicyShuffleOn():null;var b=shufBtn();"
            + "if(on===null||!b){if(n<8)setTimeout(function(){shuffleOff(n+1,seq,k);},1500);return;}"
            + "if(!on)return;var now=window.__spicyStateSeq||0;k=k||0;"
            + "if(k===0){try{if(window.spicyPlayer)spicyPlayer.postMessage('shuffle-on');}catch(x){}seq=now;}"
            + "else if(k>=3&&(k===3||now!==seq)){b.click();seq=now;}"
            + "k++;if(n<10)setTimeout(function(){shuffleOff(n+1,seq,k);},1500);}catch(e){}}"
            + "function watch(el){if(el.__spicyM)return;el.__spicyM=true;els.push(el);if(els.length>8)els.shift();"
            + "if(hush&&vd&&vd.set){try{if(el.__spicyVol===undefined)el.__spicyPre=vd.get.call(el);vd.set.call(el,0);}catch(x){}}"
            + "el.addEventListener('playing',function(){el.__spicyMoved=Date.now();el.__spicyStall=0;window.__spicyPlayAt=Date.now();post('playing');"
            + "if(!window.__spicyShufDone){window.__spicyShufDone=true;setTimeout(function(){shuffleOff(0);},1200);}});"
            + "el.addEventListener('timeupdate',function(){el.__spicyMoved=Date.now();el.__spicyStall=0;"
            + "try{if(window.__spicyAdTick)window.__spicyAdTick(el);}catch(x){}});"
            + "['pause','ended','emptied'].forEach(function(n){el.addEventListener(n,function(){post('paused');});});}"
            // Stuck: the player's own state has said "playing" on this device for a while and no
            // audio has moved all that time (seen after a hand-off: the first track resolves its
            // file, then never asks for its licence or audio). Reported once; the service reloads
            // the player and has Spotify select it again, at the same position.
            + "var lastMoving=0,stuckSent=false;"
            + "setInterval(function(){try{var now=Date.now(),moving=false;"
            + "for(var j=0;j<els.length;j++){var x=els[j];if(!x.paused&&x.__spicyMoved&&now-x.__spicyMoved<5000)moving=true;}"
            + "if(moving){lastMoving=now;stuckSent=false;return;}"
            + "var want=window.__spicyWantSince?window.__spicyWantSince():0;"
            + "if(!want||stuckSent||navigator.onLine===false||!window.__spicyDev||window.__spicyAct!==window.__spicyDev)return;"
            + "if(now-Math.max(want,lastMoving)>15000){stuckSent=true;post('stuck');}}catch(x){}},3000);"
            + "setInterval(function(){var now=Date.now();for(var i=0;i<els.length;i++){var e=els[i];"
            + "if(e.paused||e.ended||!e.__spicyMoved||e.readyState>=3||navigator.onLine===false)continue;"
            + "if(now-e.__spicyMoved<6000)continue;var n=e.__spicyStall=(e.__spicyStall||0)+1;"
            + "try{if(n===1){var p=e.play();if(p&&p.catch)p.catch(function(){});}"
            + "else if(n===3){window.dispatchEvent(new Event('online'));e.currentTime=e.currentTime;}"
            + "else if(n>=6){e.__spicyStall=0;e.__spicyMoved=now;}}catch(x){}}},3000);"
            // Not the active device any more, yet still sounding: the account moved playback
            // elsewhere (most often back to the phone after a dropped connection) and this page
            // missed or ignored it - two copies of the song at once. Only once the account has
            // said so after this page started playing, and twice in a row, so a hand-off still
            // landing is never cut.
            + "var away=0;setInterval(function(){try{var d=window.__spicyDev,a=window.__spicyAct,s=false;"
            + "for(var j=0;j<els.length;j++)if(!els[j].paused)s=true;"
            + "if(!s||!d||!a||a===d||(window.__spicyActAt||0)<=(window.__spicyPlayAt||0)){away=0;return;}"
            + "if(++away<2)return;away=0;els.forEach(function(e){try{if(!e.paused)e.pause();}catch(x){}});"
            + "post('yielded');}catch(x){}},2000);"
            + "var op=P.play;"
            + "P.play=function(){try{watch(this);}catch(e){}return op.apply(this,arguments);};"
            + "}catch(e){}})();";

    /**
     * Follows the web player's own track-playback state machine - the same data it plays
     * from - to know when an ad is the current track, and which audio files the ads are:
     * <ul>
     *   <li>state machines arrive in track-playback responses and in "replace_state" commands
     *       over the dealer socket; each lists its tracks (an ad's uri is spotify:ad:... or
     *       spotify:interruption:..., exactly what the player itself treats as an ad) and its
     *       states, each state pointing at a track;</li>
     *   <li>the current state is the state_ref's index, or - when the player moves on by
     *       itself - the state_id in the state it reports back (PUT .../state);</li>
     *   <li>the ads' file ids (and external file urls) go to the service, which answers those
     *       audio requests with silence ({@code ad-ids:}); an ad that still plays is reported
     *       as {@code ad-start} / {@code ad-ending} (its last seconds, when no ad follows) /
     *       {@code ad-end}.</li>
     * </ul>
     * Messages are only parsed when they carry a state machine or a state_ref.
     */
    private static final String AD_STATE_JS = "(function(){try{"
            + "if(window.__spicyAds)return;window.__spicyAds=true;"
            + "var AD=/^spotify:(ad|interruption):/,ID=/^[0-9A-Za-z_-]{8,128}$/;"
            + "var sm=null,cur=null,ad=false,ending=false,ids=[],sent='';"
            + "function post(s){try{if(window.spicyPlayer)spicyPlayer.postMessage(s);}catch(e){}}"
            + "function uri(st){try{var t=sm.tracks[st.track];return(t&&t.metadata&&t.metadata.uri)||'';}catch(e){return '';}}"
            + "function isAd(st){return !!st&&AD.test(uri(st));}"
            + "function next(){try{var a=sm.states;for(var i=0;i<a.length;i++)if(a[i]===cur)return a[i+1]||null;}catch(e){}return null;}"
            + "function add(v){if(typeof v==='string'&&v.length>=8&&v.length<=512&&ids.indexOf(v)<0){ids.push(v);return true;}return false;}"
            + "function harvest(m){var tr=m.tracks,ch=false;if(!tr||!tr.length)return;"
            + "for(var i=0;i<tr.length;i++){var t=tr[i];if(!t||!t.metadata||!AD.test(t.metadata.uri||''))continue;"
            + "var man=t.manifest||{};for(var k in man){var g=man[k];if(!g||!g.length||typeof g==='string')continue;"
            + "for(var j=0;j<g.length;j++){var e=g[j];if(!e)continue;var f=typeof e==='string'?e:(e.file_id||e.fileId);"
            + "if(typeof f==='string'&&ID.test(f))ch=add(f)||ch;"
            + "var u=typeof e==='object'?(e.file_url||e.fileUrl||e.url):null;"
            + "if(typeof u==='string'&&u.indexOf('spclient')<0){u=u.replace(/^https?:\\/\\//,'').split('?')[0];if(u.length>=16)ch=add(u)||ch;}}}}"
            + "if(ch){while(ids.length>48)ids.shift();var s=JSON.stringify(ids);if(s!==sent){sent=s;post('ad-ids:'+s);}}}"
            + "function setCur(st){var same=cur&&st&&cur.state_id===st.state_id;cur=st||null;if(same)return;"
            + "ending=false;var now=isAd(cur);if(now){ad=true;post('ad-start');}else if(ad){ad=false;post('ad-end');}}"
            + "function byId(id){if(!sm||!sm.states||!id)return;for(var i=0;i<sm.states.length;i++){"
            + "if(sm.states[i].state_id===id){setCur(sm.states[i]);return;}}}"
            + "window.__spicyShuffleOn=function(){if(!cur)return null;var o=cur.options||{},md=o.modes||{};"
            + "return !!o.shuffling_context||(!!md.context_enhancement&&md.context_enhancement!=='NONE');};"
            + "var wantSince=0;function wantPlay(p){if(p===false){if(!wantSince)wantSince=Date.now();}else if(p===true)wantSince=0;}"
            + "window.__spicyWantSince=function(){return cur?wantSince:0;};"
            + "function learn(m,ref,hasRef){if(!m||!m.states||!m.tracks)return;sm=m;window.__spicyStateSeq=(window.__spicyStateSeq||0)+1;harvest(m);"
            + "if(ref)wantPlay(ref.paused);else if(hasRef&&ref===null)wantSince=0;"
            + "if(ref&&typeof ref.state_index==='number')setCur(m.states[ref.state_index]);"
            + "else if(ref&&ref.state_id)byId(ref.state_id);else if(hasRef&&ref===null)setCur(null);"
            + "else if(cur)byId(cur.state_id);}"
            + "function parse(v){if(typeof v!=='string')return v;try{return JSON.parse(v);}catch(e){}"
            + "try{return JSON.parse(atob(v));}catch(e){}return null;}"
            + "function inspect(o,d){if(!o||typeof o!=='object'||d>4)return;"
            + "if(o.state_machine)learn(o.state_machine,'state_ref' in o?o.state_ref:o.updated_state_ref,'state_ref' in o);"
            + "else if(o.updated_state_ref&&o.updated_state_ref.state_id)byId(o.updated_state_ref.state_id);"
            + "['payloads','commands'].forEach(function(k){var a=o[k];if(a&&a.length&&typeof a!=='string')"
            + "for(var i=0;i<a.length;i++)inspect(parse(a[i]),d+1);});}"
            + "function text(t){if(typeof t==='string'&&(t.indexOf('state_machine')>=0||t.indexOf('state_ref')>=0)){"
            + "try{inspect(JSON.parse(t),0);}catch(e){}}}"
            + "window.__spicyAdTick=function(el){if(!ad||ending)return;var d=el.duration,t=el.currentTime;"
            + "if(!(d>4)||!isFinite(d))return;if(d-t<=2.6&&!isAd(next())){ending=true;post('ad-ending');}};"
            + "var of=window.fetch;"
            + "window.fetch=function(i,o){var u='';try{u=String(i&&i.url||i);}catch(e){}"
            + "var tp=u.indexOf('track-playback')>=0;"
            + "if(tp&&o&&typeof o.body==='string'&&o.body.indexOf('\"state_ref\"')>=0){try{var b=JSON.parse(o.body);"
            + "if(b&&b.state_ref&&b.state_ref.state_id){byId(b.state_ref.state_id);wantPlay(b.state_ref.paused);}}catch(e){}}"
            + "var p=of.apply(this,arguments);"
            + "if(tp){p.then(function(r){return r.clone().text();}).then(text,function(){});}"
            + "return p;};"
            // 'track-playback' is a URL-path marker (see the fetch wrapper above); dealer pushes
            // are JSON payloads, not URLs, and a replace_state command over the socket has no
            // reason to contain that literal substring - gating on it here silently dropped
            // every ad transition that arrived as a dealer push instead of a fetch response,
            // which is how Spotify usually delivers them. text()'s own state_machine/state_ref
            // check is the real (and sufficient) filter.
            + "var W=window.WebSocket;if(W){var N=function(a,b){var s=b===undefined?new W(a):new W(a,b);"
            + "if(String(a).indexOf('dealer')>=0){s.addEventListener('message',function(e){"
            + "var t=e&&e.data;if(typeof t==='string')text(t);});}return s;};"
            + "N.prototype=W.prototype;N.CONNECTING=0;N.OPEN=1;N.CLOSING=2;N.CLOSED=3;window.WebSocket=N;}"
            + "}catch(e){}})();";

    /** Login truth from the live page: the user widget only exists signed in, the login button
     *  only signed out; anything else is a page that hasn't rendered yet. */
    private static final String LOGIN_STATE_JS = "(function(){try{"
            + "if(document.querySelector('[data-testid=\"user-widget-link\"]'))return 'in';"
            + "if(document.querySelector('[data-testid=\"login-button\"]'))return 'out';"
            + "return 'pending';}catch(e){return 'pending';}})()";

    // Analytics + ad-audio host lists, credited to Spotilol (lyssadev/Spotilol AdBlocker.kt).
    // workbox-window is deliberately NOT blocked: Spotify lazy-loads it as a webpack chunk
    // during init and blocking it trips a ChunkLoadError -> React error boundary.
    private static final String[] ANALYTICS_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "fastly-insights.com", "sentry.io",
            "t.6sc.co", "tracker.samplicio.us", "adsrvr.org", "aet.spotify.com",
            "retargeting-pixels", "spotify.com/gabo-receiver-service/",
            "googletagmanager.com", "google-analytics.com",
            // reCAPTCHA only guards the sign-up/login forms, which the hidden player never
            // shows - on the player page it is just a busy iframe running all day.
            "google.com/recaptcha", "gstatic.com/recaptcha", "recaptcha.net",
    };
    // Never visible here: Canvas (the looping artist video) and other decorative video, each a
    // hardware video decode for the length of a track; lyrics, which only feed the hidden
    // now-playing panel with a DOM update per line.
    private static final String[] UNUSED_CONTENT_MARKERS = {
            "canvaz.scdn.co", "video.akamaized.net", "video-fa.scdn.co", "video-ak.cdn.spotify.com",
            "/color-lyrics/",
            // Cover art and artist/playlist images: the page hides every image (IDLE_RENDER_JS),
            // but a hidden image is still fetched and decoded - renderer memory for nothing.
            "i.scdn.co/image/", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com",
            "mosaic.scdn.co", "seed-mix-image.spotifycdn.com", "lineup-images.scdn.co",
            "thisis-images.spotifycdn.com", "pickasso.spotifycdn.com",
    };
    // Ad-specific hosts/paths only: generic ".../audio/<file id>" paths are how every regular
    // track streams too (audio-ak.spotifycdn.com/audio/...), so matching those swapped music.
    private static final String[] AD_AUDIO_MARKERS = {
            "scdn.co/mp3-ad/", "amillionads.com", "2mdn.net", "adxcel.com", "adstudio-assets.scdn.co",
            "mp3ad.scdn.co", "audio-ads.spotify.com", "ads-akp.spotify.com",
            "ads-fa.spotify.com", "adeventtracker.spotify.com", "pixel-static.spotify.com",
            "pixel.spotify.com", "adstudio.spotify.com", "ads.spotify.com",
    };

    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 9 * 60 * 1000L;
    /** Grace before dropping the locks on pause: a track change fires pause->playing. */
    private static final long LOCK_RELEASE_GRACE_MS = 60 * 1000L;
    private static final int WATCHDOG_MAX_RESETS = 2;
    private static final long WATCHDOG_DELAY_MS = 50 * 1000L;
    private static final long LOGIN_POLL_MS = 2500L;
    private static final int LOGIN_POLL_MAX = 16;
    /** Unused this long (nothing played, no command), the player shuts itself down to give
     *  its ~300 MB back; Spotify re-warms it the next time it comes to the front. */
    private static final long IDLE_STOP_MS = 15 * 60 * 1000L;
    private static final long IDLE_CHECK_MS = 5 * 60 * 1000L;
    private static final long DEVICE_ID_POLL_MS = 1500L;
    private static final int DEVICE_ID_POLL_MAX = 20;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean networkLost;
    private int watchdogResets;
    private android.view.WindowManager overlayWindowManager;
    private WebView overlayAttachedView;
    // Set when the platform refused foreground promotion in onCreate - any start command that
    // still lands afterwards has nothing left to run against, so it just stops the service.
    private boolean foregroundDenied;
    private boolean playing;
    private long lastActivityElapsed = SystemClock.elapsedRealtime();
    private String lastNotificationText;

    /** The latest WARMUP reply from Spotify: a channel back into Spotify's process, used to
     *  ask it to select this device again after a network change (see EVENT_RESELECT). */
    private android.os.ResultReceiver eventChannel;
    private boolean networkRecovery = true;
    private Network currentNetwork;
    private boolean playingBeforeNetworkChange;
    private int recoveryChecks;

    private boolean loginCheckRunning;

    // --- Ads (see AD_STATE_JS) ---------------------------------------------------------------
    /** Ad audio file ids / external urls the page reported; read on WebView IO threads. */
    private static volatile java.util.List<String> adAudioIds = Collections.emptyList();
    private static final String AD_MODE_OFF = "Off";
    private static final String AD_MODE_MUSIC = "Play music instead";
    /** An ad whose audio got answered with silence is over in well under this; the music only
     *  starts for an ad that is still current after it, so a blocked ad doesn't blip it. */
    private static final long AD_MUSIC_DELAY_MS = 1500L;
    private boolean adActive;
    private com.flowify.ettea.hooks.AdMusicPlayer adMusic;
    private final Runnable adMusicStart = this::startAdMusic;
    private final java.util.List<LoginCheck> loginWaiters = new java.util.ArrayList<>();


    private final Runnable reconnectRunnable = () -> {
        try {
            if (PlayerSession.webview == null) return;
            recoverConnection();
        } catch (Throwable t) {
            Log.e(TAG, "reconnect failed type=" + t.getClass().getName());
        }
    };

    private final Runnable lockRelease = this::releaseStreamingLocks;

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (!playing && SystemClock.elapsedRealtime() - lastActivityElapsed >= IDLE_STOP_MS) {
                Log.i(TAG, "idle for " + (IDLE_STOP_MS / 60000) + " min, stopping player");
                releaseStreamingLocks();
                destroyPlayer();
                stopSelf();
                exitWhenStopped();
                return;
            }
            main.postDelayed(this, IDLE_CHECK_MS);
        }
    };

    private final Runnable wakeRenew = new Runnable() {
        @Override
        public void run() {
            if (!playing) return;
            keepAwake();
            main.postDelayed(this, WAKE_LOCK_RENEW_MS);
        }
    };

    private static final long RECOVERY_CHECK_MS = 3000L;
    private static final int RECOVERY_CHECKS_BEFORE_RELOAD = 4;

    /**
     * Soft first: nudge the page ("online") and give its own dealer reconnect a few checks.
     * A page whose dealer socket still isn't open by then gets reloaded - it registers as a
     * new device, so if music was playing Spotify is asked to select it again. The Connect
     * session belongs to Spotify's servers, not the local network: nothing needs both apps on
     * the same wifi, only both reconnected.
     */
    private void recoverConnection() {
        try {
            WebView w = PlayerSession.webview;
            if (w == null) return;
            String u = PlayerSession.lastPageUrl;
            if (u == null || !u.contains("open.spotify.com")) {
                Log.i(TAG, "page broken (" + u + "), reloading player page");
                reloadForRecovery(w);
                return;
            }
            if (recoveryChecks == 0) evalJs("window.dispatchEvent(new Event('online'));");
            w.evaluateJavascript("(function(){var s=window.__spicyDealer;return s?s.readyState:-1;})()", v -> {
                boolean open = "1".equals(v);
                if (open) {
                    Log.i(TAG, "network recovery: dealer connected");
                    playingBeforeNetworkChange = false;
                    return;
                }
                if (!networkRecovery) return;
                if (++recoveryChecks < RECOVERY_CHECKS_BEFORE_RELOAD) {
                    main.postDelayed(reconnectRunnable, RECOVERY_CHECK_MS);
                    return;
                }
                Log.i(TAG, "network recovery: dealer still down, reloading player page");
                reloadForRecovery(w);
            });
        } catch (Throwable t) {
            Log.e(TAG, "recoverConnection failed type=" + t.getClass().getName());
        }
    }

    private static final long PRESENCE_CHECK_MS = 60 * 1000L;
    private static final long UNLISTED_RELOAD_MIN_GAP_MS = 3 * 60 * 1000L;
    private long lastUnlistedReload;

    /** A page whose device the account no longer lists (see DEVICE_ID_JS) looks healthy -
     *  loaded, signed in, socket open - but never shows up in Spotify's device list, so every
     *  hand-off fails with "route not found". While nothing plays, reload it: it registers
     *  again as a fresh device. */
    private final Runnable presenceCheck = new Runnable() {
        @Override
        public void run() {
            main.postDelayed(this, PRESENCE_CHECK_MS);
            WebView w = PlayerSession.webview;
            if (w == null || playing || !PlayerSession.loggedIn) return;
            w.evaluateJavascript("window.__spicyListed===false", v -> {
                if ("true".equals(v)) reloadUnlisted("presence check");
            });
        }
    };

    /** Reloads a page whose device dropped out of the account's list; false when a reload
     *  just happened (a device that keeps dropping out must not become a reload loop). */
    private boolean reloadUnlisted(String why) {
        WebView w = PlayerSession.webview;
        long now = SystemClock.elapsedRealtime();
        if (w == null || (lastUnlistedReload != 0 && now - lastUnlistedReload < UNLISTED_RELOAD_MIN_GAP_MS)) {
            return false;
        }
        lastUnlistedReload = now;
        Log.w(TAG, "device no longer listed by Spotify (" + why + "), reloading the player");
        w.loadUrl(PLAYER_HOME);
        return true;
    }

    private static final long STUCK_RELOAD_MIN_GAP_MS = 2 * 60 * 1000L;
    private long lastStuckReload;

    /** The page has been "playing" without any audio for a while (see MEDIA_STATE_JS): reload it
     *  and have Spotify select it again, which resumes at the same position. Bounded, so a
     *  track that can't play at all doesn't turn into a reload loop. */
    private void onPlaybackStuck() {
        WebView w = PlayerSession.webview;
        long now = SystemClock.elapsedRealtime();
        if (w == null || (lastStuckReload != 0 && now - lastStuckReload < STUCK_RELOAD_MIN_GAP_MS)) {
            Log.w(TAG, "playback stuck, recovery skipped (too soon)");
            return;
        }
        lastStuckReload = now;
        Log.w(TAG, "playback stuck without audio, reloading the player");
        playingBeforeNetworkChange = true;
        reloadForRecovery(w);
    }

    private void reloadForRecovery(WebView w) {
        final boolean wasPlaying = playingBeforeNetworkChange;
        playingBeforeNetworkChange = false;
        w.loadUrl(PLAYER_HOME);
        if (!wasPlaying || eventChannel == null) return;
        // The reloaded page is a new Connect device; once it has registered, have Spotify
        // select it again so the music that was playing comes back here.
        new Runnable() {
            int tries;

            @Override
            public void run() {
                WebView v = PlayerSession.webview;
                if (v == null) return;
                v.evaluateJavascript("window.__spicyDev||''", id -> {
                    String dev = id == null ? "" : id.replace("\"", "");
                    if (!dev.matches("[0-9a-f]{40}")) {
                        if (++tries < DEVICE_ID_POLL_MAX) main.postDelayed(this, DEVICE_ID_POLL_MS);
                        return;
                    }
                    android.os.Bundle data = new android.os.Bundle();
                    data.putString(EXTRA_DEVICE_ID, dev);
                    sendWarmReply(eventChannel, EVENT_RESELECT, data);
                    Log.i(TAG, "network recovery: asked Spotify to select the player again");
                });
            }
        }.run();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    /** WARMUP extra: the package whose process the reply lives in (ConnectEntry sets it). */
    private static final String EXTRA_REPLY_OWNER = "reply_owner";
    /** After Spotify is gone, how long the player lingers before giving its memory back. */
    private static final long SPOTIFY_GONE_STOP_MS = 60 * 1000L;
    private IBinder watchedSpotify;
    private final IBinder.DeathRecipient spotifyDeath = () -> main.post(this::onSpotifyGone);
    private final Runnable spotifyGoneStop = () -> {
        if (playing) return;
        Log.i(TAG, "Spotify gone, stopping player");
        releaseStreamingLocks();
        destroyPlayer();
        stopSelf();
        exitWhenStopped();
    };

    /**
     * Follows Spotify's own process through the binder behind its WARMUP reply. This device only
     * stands in for the phone's Spotify: when that is closed or killed, a player still sounding
     * on its own is just audio nobody can stop from Spotify any more - and with Spotify back, a
     * second copy of the same song. So it pauses, and shuts down a minute later.
     */
    private void watchSpotify(android.os.ResultReceiver reply) {
        IBinder binder = binderOf(reply);
        if (binder == null || binder == watchedSpotify) return;
        main.removeCallbacks(spotifyGoneStop);
        try {
            if (watchedSpotify != null) watchedSpotify.unlinkToDeath(spotifyDeath, 0);
        } catch (Throwable ignored) {
        }
        watchedSpotify = null;
        try {
            binder.linkToDeath(spotifyDeath, 0);
            watchedSpotify = binder;
        } catch (android.os.RemoteException alreadyDead) {
            main.post(this::onSpotifyGone);
        }
    }

    private void onSpotifyGone() {
        watchedSpotify = null;
        Log.i(TAG, "Spotify's process is gone" + (playing ? ", pausing" : ""));
        if (playing) pressPlay(false);
        main.removeCallbacks(spotifyGoneStop);
        main.postDelayed(spotifyGoneStop, SPOTIFY_GONE_STOP_MS);
    }

    /**
     * Ends the :player process itself once the service has stopped. It exists only for the
     * player, and a stopped service otherwise leaves it cached with its ~150 MB (the WebView
     * runtime stays loaded) until the system gets round to it. Not if a command has rebuilt the
     * player in the meantime.
     */
    private void exitWhenStopped() {
        main.postDelayed(() -> {
            if (PlayerSession.webview != null) return;
            Log.i(TAG, "player process exiting");
            android.os.Process.killProcess(android.os.Process.myPid());
        }, 1500);
    }

    /** The binder a ResultReceiver carries (it only exposes it through its parcel form). */
    private static IBinder binderOf(android.os.ResultReceiver reply) {
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            reply.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return parcel.readStrongBinder();
        } catch (Throwable t) {
            return null;
        } finally {
            parcel.recycle();
        }
    }

    /** Memory is short and nothing is playing: give the ~300 MB back; the next warm-up rebuilds. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (playing || level < TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_UI_HIDDEN) return;
        Log.i(TAG, "memory pressure (" + level + ") while idle, stopping player");
        releaseStreamingLocks();
        destroyPlayer();
        stopSelf();
        exitWhenStopped();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PlayerSession.loggedIn = hasSessionCookie();
        if (!com.flowify.ettea.ForegroundServiceGuard.promote(
                this, NOTIFICATION_ID, buildNotification(statusText()), TAG)) {
            // Nothing else in here is worth setting up if we can't be a foreground service -
            // and staying alive without reaching the foreground just earns a kill from the
            // platform watchdog a few seconds later.
            foregroundDenied = true;
            stopSelf();
            return;
        }
        watchNetwork();
        main.postDelayed(idleCheck, IDLE_CHECK_MS);
        main.postDelayed(presenceCheck, PRESENCE_CHECK_MS);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":playback");
                wakeLock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "wakeLock create failed type=" + t.getClass().getName());
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                // Deliberately not HIGH_PERF: that disables wifi power saving for the whole
                // track, and buffered audio streams fine with it on. (Android 10+ treats
                // WIFI_MODE_FULL as a no-op; older versions use it to keep wifi up.)
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG + ":stream");
                wifiLock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "wifiLock create failed type=" + t.getClass().getName());
        }
    }

    private void keepAwake() {
        try {
            if (wakeLock != null) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.e(TAG, "wakeLock acquire failed type=" + t.getClass().getName());
        }
    }

    private void acquireStreamingLocks() {
        main.removeCallbacks(lockRelease);
        keepAwake();
        try {
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable t) {
            Log.e(TAG, "wifiLock acquire failed type=" + t.getClass().getName());
        }
    }

    private void releaseStreamingLocks() {
        main.removeCallbacks(lockRelease);
        main.removeCallbacks(wakeRenew);
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable t) {
            Log.e(TAG, "wakeLock release failed type=" + t.getClass().getName());
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable t) {
            Log.e(TAG, "wifiLock release failed type=" + t.getClass().getName());
        }
    }

    /** Audio really started/stopped in the page (whoever asked for it - us or Connect). */
    private void onMediaState(boolean nowPlaying) {
        lastActivityElapsed = SystemClock.elapsedRealtime();
        if (nowPlaying == playing) return;
        playing = nowPlaying;
        Log.i(TAG, "media " + (nowPlaying ? "playing" : "paused"));
        if (adActive && adMusic != null) {
            if (nowPlaying) {
                if (AD_MODE_MUSIC.equals(PlayerSession.adMode(this))) adMusic.fadeIn();
            } else {
                adMusic.hold();
            }
        }
        if (nowPlaying) {
            acquireStreamingLocks();
            main.removeCallbacks(wakeRenew);
            main.postDelayed(wakeRenew, WAKE_LOCK_RENEW_MS);
        } else {
            main.removeCallbacks(wakeRenew);
            main.removeCallbacks(lockRelease);
            main.postDelayed(lockRelease, LOCK_RELEASE_GRACE_MS);
        }
        postNotification();
    }

    private void watchNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLost(Network network) {
                    if (!network.equals(currentNetwork)) return;
                    networkLost = true;
                    main.post(() -> playingBeforeNetworkChange |= playing);
                    Log.i(TAG, "network lost");
                }

                // Called for the new default network on every switch - wifi to wifi, wifi to
                // mobile - often without an onLost for the old one first. Every socket the page
                // holds belongs to the old network, so each switch counts as a reconnect.
                @Override
                public void onAvailable(Network network) {
                    Network previous = currentNetwork;
                    currentNetwork = network;
                    if (previous == null && !networkLost) return;
                    if (network.equals(previous) && !networkLost) return;
                    networkLost = false;
                    Log.i(TAG, "network changed, reconnecting player shortly");
                    main.post(() -> {
                        playingBeforeNetworkChange |= playing;
                        recoveryChecks = 0;
                        main.removeCallbacks(reconnectRunnable);
                        main.postDelayed(reconnectRunnable, 2500);
                    });
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable t) {
            Log.e(TAG, "watchNetwork failed type=" + t.getClass().getName());
        }
    }

    private void unwatchNetwork() {
        try {
            main.removeCallbacks(reconnectRunnable);
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) {
        } finally {
            networkCallback = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (foregroundDenied) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? null : intent.getAction();
        lastActivityElapsed = SystemClock.elapsedRealtime();
        PlayerSession.noteUiLanguage(this, intent);
        PlayerSession.noteAdSettings(this, intent);
        if (action == null) {
            // START_STICKY redelivery after the system killed the process: the service was
            // running, so bring the player back instead of idling as an empty foreground
            // service with no device behind it.
            Log.i(TAG, "sticky restart, rebuilding player");
            ensureReady(() -> checkLogin(null));
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            releaseStreamingLocks();
            destroyPlayer();
            stopSelf();
            exitWhenStopped();
            return START_NOT_STICKY;
        }
        if (ACTION_WARMUP.equals(action)) {
            android.os.ResultReceiver reply;
            try {
                reply = intent.getParcelableExtra(EXTRA_WARM_REPLY);
            } catch (Throwable ignored) {
                reply = null;
            }
            final android.os.ResultReceiver pending = reply;
            if (reply != null) eventChannel = reply;
            if (reply != null && SPOTIFY_PACKAGE.equals(intent.getStringExtra(EXTRA_REPLY_OWNER))) {
                watchSpotify(reply);
            }
            networkRecovery = intent.getBooleanExtra(EXTRA_NETWORK_RECOVERY, networkRecovery);
            final boolean needDevice = intent.getBooleanExtra(EXTRA_NEED_DEVICE_ID, false);
            if (PlayerSession.webview == null || !PlayerSession.loggedIn || !hasSessionCookie()) {
                sendWarmReply(reply, WARM_RESULT_STARTING, null);
            }
            ensureReady(() -> checkLogin(ok -> {
                if (ok) replyReady(pending, needDevice);
                else sendWarmReply(pending, WARM_RESULT_LOGIN_REQUIRED, null);
            }));
            return START_STICKY;
        }
        if (ACTION_SIGNED_OUT.equals(action)) {
            PlayerSession.loggedIn = false;
            postNotification();
            // Drop the in-memory session the page still holds, so it can't keep acting as the
            // old account's device.
            WebView w = PlayerSession.webview;
            if (w != null) w.loadUrl(PLAYER_HOME);
            return START_STICKY;
        }
        if (ACTION_LOGIN_DONE.equals(action)) {
            PlayerSession.loggedIn = true;
            postNotification();
            // The parked page predates the session - reload it signed in.
            ensureReady(() -> {
                WebView w = PlayerSession.webview;
                if (w != null) w.loadUrl(PLAYER_HOME);
            });
            return START_STICKY;
        }
        ensureReady(() -> {
            if (ACTION_LOGIN.equals(action)) {
                launchLogin();
                return;
            }
            if (ACTION_RECONNECT.equals(action)) {
                recoverConnection();
                checkLogin(ok -> {
                    if (!ok) launchLogin();
                });
                return;
            }
            dispatch(action, intent);
        });
        return START_STICKY;
    }

    private interface LoginCheck {
        void onResult(boolean loggedIn);
    }

    /**
     * sp_dc is Spotify's long-lived web session cookie: present only for an authenticated
     * spotify.com session, persisted to disk by CookieManager across our own restarts, and
     * cleared by a real sign-out. Its absence is a definite "signed out"; its presence is
     * "signed in" unless the live page says otherwise (a session revoked server-side).
     */
    static boolean hasSessionCookie() {
        try {
            String cookies = CookieManager.getInstance().getCookie("https://open.spotify.com");
            return cookies != null && cookies.contains("sp_dc=");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Cookie first (instant, and the definite answer when absent), then the live page to
     * confirm - polled until it renders either the user widget or the login button, since a
     * half-painted SPA proves nothing either way. A page that never decides keeps the cookie's
     * answer rather than defaulting to "signed out".
     */
    private void checkLogin(LoginCheck callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> checkLogin(callback));
            return;
        }
        boolean cookie = hasSessionCookie();
        if (!cookie || PlayerSession.webview == null) {
            setLoggedIn(cookie);
            if (callback != null) callback.onResult(cookie);
            return;
        }
        // One page poll at a time: page loads, warm-ups and sticky restarts all ask at once.
        if (callback != null) loginWaiters.add(callback);
        if (loginCheckRunning) return;
        loginCheckRunning = true;
        new Runnable() {
            int tries;

            @Override
            public void run() {
                WebView w = PlayerSession.webview;
                if (w == null) {
                    finish(hasSessionCookie());
                    return;
                }
                try {
                    w.evaluateJavascript(LOGIN_STATE_JS, value -> {
                        String state = value == null ? "" : value.replace("\"", "");
                        if ("in".equals(state)) {
                            finish(true);
                        } else if ("out".equals(state)) {
                            finish(false);
                        } else if (++tries >= LOGIN_POLL_MAX) {
                            finish(hasSessionCookie());
                        } else {
                            main.postDelayed(this, LOGIN_POLL_MS);
                        }
                    });
                } catch (Throwable t) {
                    finish(hasSessionCookie());
                }
            }

            private void finish(boolean ok) {
                loginCheckRunning = false;
                Log.i(TAG, "login check loggedIn=" + ok);
                setLoggedIn(ok);
                java.util.List<LoginCheck> waiters = new java.util.ArrayList<>(loginWaiters);
                loginWaiters.clear();
                for (LoginCheck waiter : waiters) {
                    try {
                        waiter.onResult(ok);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }.run();
    }

    private void setLoggedIn(boolean value) {
        PlayerSession.loggedIn = value;
        postNotification();
    }

    // --- Device id -------------------------------------------------------------------------

    /** READY with the device's state. A hand-off needs the device id, which a freshly loaded
     *  page only has a few seconds in, so those callers wait for it (bounded); status queries
     *  get whatever is known right away. */
    private void replyReady(android.os.ResultReceiver reply, boolean needDevice) {
        if (reply == null) return;
        new Runnable() {
            int tries;

            @Override
            public void run() {
                WebView w = PlayerSession.webview;
                if (w == null) {
                    sendWarmReply(reply, WARM_RESULT_READY, null);
                    return;
                }
                w.evaluateJavascript("(window.__spicyDev||'')+','+(window.__spicyAct||'')+','"
                        + "+(window.__spicyListed===false?'gone':'')", value -> {
                    String[] ids = (value == null ? ",," : value.replace("\"", "")).split(",", -1);
                    String dev = ids.length > 0 && ids[0].matches("[0-9a-f]{40}") ? ids[0] : null;
                    String act = ids.length > 1 && ids[1].matches("[0-9a-f]+") ? ids[1] : null;
                    boolean gone = ids.length > 2 && "gone".equals(ids[2]);
                    // Handing playback to an id Spotify no longer lists can only fail: register
                    // again first and hand over the new id. The first poll waits for the reload
                    // to replace the old document, which would still answer with the old id.
                    if (gone && needDevice && !playing && reloadUnlisted("hand-off")) {
                        main.postDelayed(this, 2 * DEVICE_ID_POLL_MS);
                        return;
                    }
                    if (dev == null && needDevice && ++tries < DEVICE_ID_POLL_MAX) {
                        main.postDelayed(this, DEVICE_ID_POLL_MS);
                        return;
                    }
                    if (dev == null && needDevice) {
                        Log.w(TAG, "device id still unknown after the wait, page=" + PlayerSession.lastPageUrl
                                + " raw=" + value);
                    }
                    android.os.Bundle data = new android.os.Bundle();
                    if (dev != null) data.putString(EXTRA_DEVICE_ID, dev);
                    if (act != null) data.putString(EXTRA_ACTIVE_DEVICE_ID, act);
                    data.putBoolean(EXTRA_PLAYING, playing);
                    sendWarmReply(reply, WARM_RESULT_READY, data);
                });
            }
        }.run();
    }

    private void sendWarmReply(android.os.ResultReceiver reply, int code, android.os.Bundle data) {
        try {
            if (reply != null) reply.send(code, data);
        } catch (Throwable ignored) {
        }
    }

    private void launchLogin() {
        postNotification();
        try {
            Intent i = new Intent(this, WebLoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "launchLogin startActivity failed type=" + t.getClass().getName());
        }
    }

    // --- Ads ---------------------------------------------------------------------------------

    private void onAdIds(String json) {
        try {
            org.json.JSONArray array = new org.json.JSONArray(json);
            java.util.List<String> ids = new java.util.ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, "");
                if (id.length() >= 8) ids.add(id);
            }
            adAudioIds = Collections.unmodifiableList(ids);
            Log.i(TAG, "ad audio ids known=" + ids.size());
        } catch (Throwable t) {
            Log.e(TAG, "ad ids unreadable type=" + t.getClass().getName());
        }
    }

    /** An ad is the page's current track. Its audio is normally answered with silence already
     *  (see shouldInterceptRequest); this covers one that still plays, the way Settings' "Ads"
     *  asks for: nothing, silence, or music in its place. */
    private void onAdStart() {
        String mode = PlayerSession.adMode(this);
        boolean wasActive = adActive;
        if (mode == null || AD_MODE_OFF.equals(mode)) {
            if (wasActive) onAdEnd();
            return;
        }
        adActive = true;
        if (!wasActive) Log.i(TAG, "ad started, mode=" + mode);
        evalJs("window.__spicyHush&&window.__spicyHush(true);");
        if (!AD_MODE_MUSIC.equals(mode)) return;
        if (adMusic != null && adMusic.isPlaying()) {
            adMusic.fadeIn(); // next ad of the break: carries on (turns an ending around)
            return;
        }
        main.removeCallbacks(adMusicStart);
        main.postDelayed(adMusicStart, AD_MUSIC_DELAY_MS);
    }

    private void startAdMusic() {
        if (!adActive || !AD_MODE_MUSIC.equals(PlayerSession.adMode(this))) return;
        if (adMusic == null) adMusic = new com.flowify.ettea.hooks.AdMusicPlayer();
        adMusic.setTheme(PlayerSession.adMusicTheme(this));
        if (playing) adMusic.fadeIn();
    }

    /** The break's last ad is about to end: the music's ending plays out before the song. */
    private void onAdEnding() {
        main.removeCallbacks(adMusicStart);
        if (adMusic != null) adMusic.fadeOutAndStop();
    }

    private void onAdEnd() {
        main.removeCallbacks(adMusicStart);
        if (adMusic != null) adMusic.fadeOutAndStop();
        if (!adActive) return;
        adActive = false;
        Log.i(TAG, "ad ended");
        evalJs("window.__spicyHush&&window.__spicyHush(false);");
    }

    private void dispatch(String action, Intent intent) {
        WebView w = PlayerSession.webview;
        if (w == null) return;
        switch (action) {
            case ACTION_OPEN: {
                String uri = intent.getStringExtra("uri");
                if (uri != null && !uri.isEmpty()) {
                    Log.i(TAG, "open " + toOpenUrl(uri));
                    w.loadUrl(toOpenUrl(uri));
                }
                break;
            }
            case ACTION_PLAY:
                pressPlay(true);
                break;
            case ACTION_PAUSE:
                pressPlay(false);
                break;
            case ACTION_TOGGLE:
                evalJs("var b=document.querySelector('[data-testid=\"control-button-playpause\"]');if(b)b.click();");
                break;
            case ACTION_NEXT:
                evalJs("var b=document.querySelector('[data-testid=\"control-button-skip-forward\"]');if(b)b.click();");
                break;
            case ACTION_PREVIOUS:
                evalJs("var b=document.querySelector('[data-testid=\"control-button-skip-back\"]');if(b)b.click();");
                break;
            case ACTION_SEEK: {
                int pos = intent.getIntExtra("position", 0);
                evalJs("(function(){var s=document.querySelector('[data-testid=\"progress-bar\"] input[type=\"range\"]');if(!s)return;var m=+s.max||100;s.focus();s.value=Math.max(0,Math.min(m," + pos + "));s.dispatchEvent(new Event('input',{bubbles:true}));s.dispatchEvent(new Event('change',{bubbles:true}));})()");
                break;
            }
            case ACTION_VOLUME: {
                int vol = intent.getIntExtra("volume", 50);
                evalJs("(function(){var s=document.querySelector('[data-testid=\"volume-bar\"] input[type=\"range\"]');if(!s)return;var m=+s.max||100;s.value=Math.round(m*" + vol + "/100);s.dispatchEvent(new Event('input',{bubbles:true}));s.dispatchEvent(new Event('change',{bubbles:true}));})()");
                break;
            }
            case ACTION_SEARCH: {
                String q = intent.getStringExtra("q");
                if (q != null && !q.isEmpty()) {
                    try {
                        w.loadUrl("https://open.spotify.com/search/" + java.net.URLEncoder.encode(q, "UTF-8"));
                    } catch (Throwable ignored) {
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private void pressPlay(boolean wantPlay) {
        Log.i(TAG, "pressPlay want=" + wantPlay);
        evalJs("(function(){var b=document.querySelector('[data-testid=\"control-button-playpause\"]');if(!b)return;var l=(b.getAttribute('aria-label')||'').toLowerCase();var isPlay=l.indexOf('play')>=0&&l.indexOf('pause')<0;if(" + wantPlay + "&&isPlay)b.click();if(!" + wantPlay + "&&!isPlay)b.click();})()");
    }

    private String toOpenUrl(String uri) {
        String u = uri.trim();
        if (u.startsWith("http")) return u;
        if (u.startsWith("spotify:track:")) return "https://open.spotify.com/track/" + u.substring(14);
        if (u.startsWith("spotify:album:")) return "https://open.spotify.com/album/" + u.substring(14);
        if (u.startsWith("spotify:playlist:")) return "https://open.spotify.com/playlist/" + u.substring(17);
        if (u.startsWith("spotify:artist:")) return "https://open.spotify.com/artist/" + u.substring(15);
        return HOME;
    }

    private void evalJs(String script) {
        try {
            WebView w = PlayerSession.webview;
            if (w == null) return;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(() -> evalJs(script));
                return;
            }
            w.evaluateJavascript(script, null);
        } catch (Throwable t) {
            Log.e(TAG, "evalJs failed type=" + t.getClass().getName());
        }
    }

    private interface Ready {
        void run();
    }

    /**
     * Adds the WebView to a real system window (1x1px, fully transparent, touch-through) instead
     * of leaving it purely headless. android.permission.SYSTEM_ALERT_WINDOW is a special
     * permission the user must grant manually via Settings - if it isn't granted yet, this is a
     * no-op and playback stays headless.
     */
    private void attachToOverlayWindow(WebView w) {
        try {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.w(TAG, "overlay permission not granted - player runs headless");
                return;
            }
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : android.view.WindowManager.LayoutParams.TYPE_PHONE;
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    1, 1, type,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            wm.addView(w, lp);
            if (Build.VERSION.SDK_INT >= 35) {
                // Votes only for this invisible 1x1 view; other windows keep their own rates.
                // Looked up at runtime: the constant is missing from some SDK stubs (e.g. the
                // android.jar Termux builds use), which failed compilation there.
                try {
                    float low = android.view.View.class
                            .getField("REQUESTED_FRAME_RATE_CATEGORY_LOW").getFloat(null);
                    android.view.View.class.getMethod("setRequestedFrameRate", float.class)
                            .invoke(w, low);
                } catch (Throwable ignored) {
                }
            }
            overlayWindowManager = wm;
            overlayAttachedView = w;
            Log.i(TAG, "webview attached to overlay window");
        } catch (Throwable t) {
            Log.e(TAG, "attachToOverlayWindow failed type=" + t.getClass().getName(), t);
        }
    }

    private void detachOverlayWindow() {
        if (overlayWindowManager == null || overlayAttachedView == null) return;
        try {
            overlayWindowManager.removeViewImmediate(overlayAttachedView);
        } catch (Throwable ignored) {
        }
        overlayWindowManager = null;
        overlayAttachedView = null;
    }

    private void ensureReady(Ready onReady) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> ensureReady(onReady));
            return;
        }
        try {
            if (PlayerSession.webview != null) {
                onReady.run();
                return;
            }
            WebView w = new WebView(getApplicationContext());
            // No extra offscreen layer: a hardware layer only adds a GPU texture to update per
            // frame for a 1x1 view nobody looks at (drawing itself stays hardware-accelerated).
            w.setLayerType(android.view.View.LAYER_TYPE_NONE, null);
            // The renderer is what plays the audio: keep it bound as important even though the
            // view is never really visible, so the system neither deprioritises it (stutter)
            // nor reclaims it first under memory pressure (silence until rebuilt).
            try {
                w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            } catch (Throwable ignored) {
            }
            attachToOverlayWindow(w);
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            // Popups are swallowed (no onCreateWindow) rather than navigating the player away.
            s.setSupportMultipleWindows(true);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setGeolocationEnabled(false);
            s.setSaveFormData(false);
            // Cover art, avatars and playlist mosaics decode and upload to the GPU for a page
            // nobody sees; playback, login and Connect never need them.
            s.setBlockNetworkImage(true);
            try {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(w, true);
            } catch (Throwable ignored) {
            }
            BrowserDisguise.applyDesktop(this, w);
            java.util.Set<String> player = Collections.singleton("https://open.spotify.com");
            BrowserDisguise.addDocumentStart(w, IDLE_RENDER_JS, player);
            BrowserDisguise.addDocumentStart(w, DEVICE_ID_JS, player);
            BrowserDisguise.addDocumentStart(w, AD_STATE_JS, player);
            installMediaBridge(w);
            w.setWebViewClient(new PlayerWebClient());
            w.setWebChromeClient(new android.webkit.WebChromeClient() {
                // Surfaces the page's own errors (its EME/DRM rejection reason included) to
                // logcat. Errors only: the SPA's warnings repeat every few seconds all day.
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                    if (cm.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        Log.i(TAG, "console[ERROR] " + cm.message());
                    }
                    return true;
                }

                // Without this, WebView silently denies every permission request - including
                // RESOURCE_PROTECTED_MEDIA_ID, which Chromium's EME/Widevine path asks for
                // before it will play anything but previews.
                @Override
                public void onPermissionRequest(android.webkit.PermissionRequest request) {
                    if (request == null) return;
                    try {
                        request.grant(request.getResources());
                    } catch (Throwable t) {
                        request.deny();
                    }
                }
            });
            PlayerSession.webview = w;
            PlayerSession.lastPageUrl = "";
            w.loadUrl(PLAYER_HOME);
            Log.i(TAG, "player created");
            armWatchdog(w);
            onReady.run();
        } catch (Throwable t) {
            Log.e(TAG, "ensureReady failed type=" + t.getClass().getName(), t);
            PlayerSession.webview = null;
        }
    }

    private void installMediaBridge(WebView w) {
        java.util.Set<String> player = Collections.singleton("https://open.spotify.com");
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(w, "spicyPlayer", player,
                        (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                            String data = message == null ? null : message.getData();
                            if ("playing".equals(data)) onMediaState(true);
                            else if ("paused".equals(data)) onMediaState(false);
                            else if ("stuck".equals(data)) onPlaybackStuck();
                            else if ("yielded".equals(data)) {
                                Log.i(TAG, "another device is active, stopped this one's audio");
                                onMediaState(false);
                            }
                            else if ("shuffle-on".equals(data)) {
                                Log.i(TAG, "shuffle on here, asking Spotify to turn it off");
                                sendWarmReply(eventChannel, EVENT_SHUFFLE_OFF, null);
                            }
                            else if ("ad-start".equals(data)) onAdStart();
                            else if ("ad-ending".equals(data)) onAdEnding();
                            else if ("ad-end".equals(data)) onAdEnd();
                            else if (data != null && data.startsWith("ad-ids:")) onAdIds(data.substring(7));
                            else if ("logout-blocked".equals(data)) {
                                Log.w(TAG, "ignored a remote sign-out command for this device");
                            }
                        });
            }
        } catch (Throwable t) {
            Log.e(TAG, "media bridge failed type=" + t.getClass().getName());
        }
        BrowserDisguise.addDocumentStart(w, MEDIA_STATE_JS, player);
    }

    /**
     * A player that never reports any page never loaded anything - reset it bounded so the
     * next command rebuilds genuinely fresh instead of talking to a corpse forever.
     */
    private void armWatchdog(WebView created) {
        main.postDelayed(() -> {
            try {
                if (watchdogResets >= WATCHDOG_MAX_RESETS) return;
                if (PlayerSession.webview != created || created == null) return;
                if (!PlayerSession.lastPageUrl.isEmpty()) return;
                Log.w(TAG, "watchdog: player never loaded, rebuilding");
                destroyPlayer();
                watchdogResets++;
                ensureReady(() -> checkLogin(null));
            } catch (Throwable ignored) {
            }
        }, WATCHDOG_DELAY_MS);
    }

    private void destroyPlayer() {
        try {
            WebView w = PlayerSession.webview;
            PlayerSession.webview = null;
            if (playing) onMediaState(false);
            onAdEnd();
            adAudioIds = Collections.emptyList();
            detachOverlayWindow();
            if (w != null) {
                try {
                    w.stopLoading();
                } catch (Throwable ignored) {
                }
                try {
                    w.destroy();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private final class PlayerWebClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Log.i(TAG, "location=" + url);
            PlayerSession.notePageUrl(url);
            // Fallback for WebView builds without DOCUMENT_START_SCRIPT support: later than
            // ideal (page scripts may already be running) but better than nothing.
            if (!BrowserDisguise.documentStartSupported()) {
                try {
                    view.evaluateJavascript(BrowserDisguise.lateDesktopJs(WebPlayerService.this), null);
                    if (url != null && url.startsWith("https://open.spotify.com")) {
                        view.evaluateJavascript(IDLE_RENDER_JS, null);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            PlayerSession.notePageUrl(url);
            if (url != null && url.startsWith("https://open.spotify.com")) checkLogin(null);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Second line behind the dropped log_out command: whatever else might steer the
            // hidden player into signing out, it never gets to navigate there.
            try {
                String path = request == null || request.getUrl() == null ? null : request.getUrl().getPath();
                if (path != null && path.toLowerCase(Locale.ROOT).contains("logout")) {
                    Log.w(TAG, "blocked a sign-out navigation in the player");
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            super.onReceivedError(view, request, error);
            try {
                if (request != null && request.isForMainFrame()) {
                    Log.e(TAG, "page error code=" + error.getErrorCode());
                    // Offline at load time: the network callback reloads once we're back.
                    networkLost = true;
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            try {
                if (request != null && request.isForMainFrame() && errorResponse != null
                        && errorResponse.getStatusCode() >= 400) {
                    Log.e(TAG, "page http error status=" + errorResponse.getStatusCode());
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            boolean crashed = false;
            try {
                crashed = detail != null && detail.didCrash();
            } catch (Throwable ignored) {
            }
            Log.e(TAG, "renderer gone crashed=" + crashed + ", rebuilding player");
            destroyPlayer();
            // A renderer the system reclaimed for memory mid-session: come back as a device
            // (and as the active one again if we were) instead of silently disappearing.
            main.postDelayed(() -> ensureReady(() -> checkLogin(null)), 3000);
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                if (request == null || request.getUrl() == null) return null;
                String url = request.getUrl().toString();
                String lower = url.toLowerCase(Locale.ROOT);
                for (String host : ANALYTICS_HOSTS) {
                    if (lower.contains(host)) {
                        // A believable success: an answer the page can't read (CORS) or that
                        // looks like a failure only makes it retry the upload every few seconds.
                        return new WebResourceResponse("application/json", "utf-8", 200, "OK",
                                corsHeaders(request), new ByteArrayInputStream("{}".getBytes()));
                    }
                }
                for (String marker : UNUSED_CONTENT_MARKERS) {
                    if (lower.contains(marker)) {
                        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                                corsHeaders(request), new ByteArrayInputStream(new byte[0]));
                    }
                }
                boolean adAudio = false;
                for (String marker : AD_AUDIO_MARKERS) {
                    if (lower.contains(marker)) {
                        adAudio = true;
                        break;
                    }
                }
                // Ads mostly stream from the same CDN paths as music (".../audio/<file id>"),
                // so the hosts above miss them; the ad file ids the page's own state machine
                // listed (AD_STATE_JS) don't. Never the spclient API calls that merely carry
                // an id (storage-resolve), only the media fetch itself.
                if (!adAudio && !lower.contains("spclient") && !lower.contains("storage-resolve")) {
                    java.util.List<String> ids = adAudioIds;
                    for (int i = 0; i < ids.size(); i++) {
                        if (url.contains(ids.get(i))) {
                            adAudio = true;
                            break;
                        }
                    }
                }
                if (adAudio && !lower.contains("podz-content") && !lower.contains("spclient")) {
                    Log.i(TAG, "ad audio swapped: " + summarizedUrl(url));
                    try {
                        return silentAudio(view, request);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        /** silent.mp3 in place of an ad's audio. The player streams audio with Range requests
         *  (fetch into its media source), so a ranged request gets the matching 206 slice -
         *  a plain 200 there can read as a broken stream and stop playback, not just the ad. */
        private WebResourceResponse silentAudio(WebView view, WebResourceRequest request) throws java.io.IOException {
            byte[] data;
            try (InputStream in = view.getContext().getAssets().open("silent.mp3")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                data = out.toByteArray();
            }
            Map<String, String> headers = corsHeaders(request);
            headers.put("Accept-Ranges", "bytes");
            headers.put("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges");
            String range = null;
            Map<String, String> in = request.getRequestHeaders();
            if (in != null) {
                for (Map.Entry<String, String> e : in.entrySet()) {
                    if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
                }
            }
            int length = data.length;
            java.util.regex.Matcher m = range == null ? null
                    : java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range.trim());
            if (m == null || !m.matches() || m.group(1).isEmpty()) {
                return new WebResourceResponse("audio/mpeg", null, 200, "OK", headers,
                        new ByteArrayInputStream(data));
            }
            long start = Long.parseLong(m.group(1));
            if (start >= length) {
                headers.put("Content-Range", "bytes */" + length);
                return new WebResourceResponse("audio/mpeg", null, 416, "Range Not Satisfiable", headers,
                        new ByteArrayInputStream(new byte[0]));
            }
            long end = m.group(2).isEmpty() ? length - 1 : Math.min(length - 1, Long.parseLong(m.group(2)));
            int from = (int) start;
            int count = (int) (end - start + 1);
            headers.put("Content-Range", "bytes " + from + "-" + (from + count - 1) + "/" + length);
            return new WebResourceResponse("audio/mpeg", null, 206, "Partial Content", headers,
                    new ByteArrayInputStream(data, from, count));
        }

        /** Enough CORS for credentialed, header-carrying requests (and their preflights):
         *  "*" is not accepted for either once credentials or Authorization are involved. */
        private Map<String, String> corsHeaders(WebResourceRequest request) {
            String origin = null;
            String wanted = null;
            Map<String, String> in = request.getRequestHeaders();
            if (in != null) {
                for (Map.Entry<String, String> e : in.entrySet()) {
                    if ("origin".equalsIgnoreCase(e.getKey())) origin = e.getValue();
                    else if ("access-control-request-headers".equalsIgnoreCase(e.getKey())) wanted = e.getValue();
                }
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", origin != null ? origin : "https://open.spotify.com");
            headers.put("Access-Control-Allow-Credentials", "true");
            headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            headers.put("Access-Control-Allow-Headers", wanted != null ? wanted
                    : "authorization, content-type, client-token, app-platform, spotify-app-version");
            headers.put("Access-Control-Max-Age", "86400");
            return headers;
        }

        private String summarizedUrl(String url) {
            return url.length() > 120 ? url.substring(0, 120) + "..." : url;
        }
    }

    private void createChannel() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        text(com.flowify.ettea.R.string.connect_player_channel),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                NotificationManager m = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (m != null) m.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {
        }
    }

    private String text(int id) {
        return PlayerSession.resources(this).getString(id);
    }

    private String statusText() {
        if (!PlayerSession.loggedIn) return text(com.flowify.ettea.R.string.connect_player_status_login);
        if (playing) return text(com.flowify.ettea.R.string.connect_player_status_playing);
        return text(com.flowify.ettea.R.string.connect_player_status_ready);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play);
        b.setContentTitle(text(com.flowify.ettea.R.string.connect_player_title));
        b.setContentText(text);
        b.setOngoing(false);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        if (!PlayerSession.loggedIn) {
            try {
                Intent i = new Intent(this, WebLoginActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(PendingIntent.getActivity(this, 0, i, piFlags));
            } catch (Throwable ignored) {
            }
        }
        try {
            if (playing) {
                Intent pause = new Intent(this, WebPlayerService.class).setAction(ACTION_PAUSE);
                b.addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        text(com.flowify.ettea.R.string.connect_player_pause),
                        PendingIntent.getService(this, 11, pause, piFlags)).build());
            }
            Intent stop = new Intent(this, WebPlayerService.class).setAction(ACTION_STOP);
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    text(com.flowify.ettea.R.string.connect_player_stop),
                    PendingIntent.getService(this, 12, stop, piFlags)).build());
        } catch (Throwable ignored) {
        }
        return b.build();
    }

    /** Re-posts only when the text actually changed - page loads and login checks call this
     *  constantly, and every notify() is a binder call plus a SystemUI redraw. */
    private void postNotification() {
        try {
            String text = statusText();
            if (text.equals(lastNotificationText)) return;
            lastNotificationText = text;
            NotificationManager m = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (m != null) m.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        unwatchNetwork();
        releaseStreamingLocks();
        destroyPlayer();
        super.onDestroy();
    }
}
