package com.smali_generator;

/**
 * One patch. Implementations are registered in InitProviderReshet.hooks and
 * their load() is called once, early, from the injected ContentProvider.
 *
 * load() must never throw: it runs during app startup. Wrap the body in
 * try/catch and log under the "PATCH" tag, on success as well as failure.
 */
public interface Hook {

    public void load();

    public void unload();
}
