package com.opencode.eclipse.core;

/** Native file attachment accepted by OpenCode prompt and command APIs. */
public record FilePartInput(String mime, String filename, String url) { }
