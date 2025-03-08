# JavaScriptZ-Expansion
Adds javascript placeholders.

JavascriptZ additionally provides a v8-based JavaScript engine to provide faster response times 
and is fully compatible with previous JavaScript extensions.

V8 may take up more memory, but it can bring very impressive response time 
(in my test, for the same piece of code, V8 is nearly ten times faster than QuickJS)

**NOTE**: Nashorn engine will not work with JVM 15 and above!

## Notes
This is one of my interest forks, so it may be messy. 

Some APIs and behaviors of v8 may be different from quickjs and nashorn, you need to be prepared for debugging.

GraalJS cannot be implemented for the time being.

For specific compatible architectures of v8, see [Javet - Major Features](https://github.com/caoccao/Javet#major-features).

nashorn, quickjs have been upgraded to newer versions.

Since JavaScriptZ switches dependencies to download from central,
if your network connection to central is terrible, you will need to manually
switch mirrors in the configuration file.

Dependency downloads are only triggered the first time a JavaScript engine is used
(e.g., installing JSZ, switching the engine used by JSZ).

## Config
```yaml
expansions:
  javascript:
    enable_parse_command: true
    debug: false
    github_script_downloads: false
    argument_split: ','
    # Support : nashorn, quickjs (default), v8_node, v8 (pure)
    js_engine: v8
    v8_use_gc_before_engine_close: false
    # When your network cannot connect to central or is very slow, please switch the mirror here
    mirror: https://repo.maven.apache.org/maven2/
```

## Download && Issues
If you find issues, please report them to me, not PAPI, 
as they may have been introduced by my changes and I need to confirm who caused the issue.

[Download](https://github.com/404Setup/Placeholder-JavascriptZ/releases)