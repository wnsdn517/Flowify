package com.flowify.ettea.hooks;

import com.flowify.ettea.xposed.XpPackage;
import com.flowify.ettea.xposed.SpotifySymbolResolver;

public abstract class SpotifyHook {
    protected XpPackage lpparm;
    protected SpotifySymbolResolver symbols;

    public void init(XpPackage lpparm, SpotifySymbolResolver symbols) {
        this.lpparm = lpparm;
        this.symbols = symbols;
        hook();
    }

    protected abstract void hook();
}
