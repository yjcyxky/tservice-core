# tservice-core
[![Build Status](https://travis-ci.org//.svg?branch=master)](https://travis-ci.org//)
[![codecov](https://codecov.io/gh///branch/master/graph/badge.svg)](https://codecov.io/gh//)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.yjcyxky/tservice-core.svg)](https://clojars.org/org.clojars.yjcyxky/tservice-core)

A plugin system for managing several types of plugins, such as deploying http/async/dag task.

```clj
[com.github.yjcyxky/tservice-core "0.2.2"]
```

## Convention is Better than Configuration

### Home Directory for Plugins

`${TSERVICE_PLUGIN_PATH}/plugins`

### Cache Directory

`${TSERVICE_PLUGIN_PATH}/plugins/cache`

> NOTE: You need to set RENV_PATHS_CACHE as `${TSERVICE_PLUGIN_PATH}/plugins/cache` in Dockerfile or on your OS before launching the TService.

### Environment Directory for Each Plugin

`${TSERVICE_PLUGIN_PATH}/plugins/envs/<PLUGIN_NAME>`

For detecting environments correctly, you need to set `PATH` and `R_PROFILE_USER` variables when you call the command in shell.

PATH - `${TSERVICE_PLUGIN_PATH}/plugins/envs/<PLUGIN_NAME>/bin`

R_PROFILE_USER - `${TSERVICE_PLUGIN_PATH}/plugins/envs/<PLUGIN_NAME>/Rprofile`


## Plugin Development

### How to build dependencies for your plugin?

1. Prepare a requirements.txt file for your python dependencies.
2. Initialize a renv environment for your R dependencies. 

> NOTE: Don't contain library, staging, local, lock and python directories into your jar package.

> NOTE: These files/directories need to be placed in the resource directory

### How to register a plugin?

You need to prepare a file named `tservice-plugin.yaml`. The definition information you need to know as bellow:

```yaml
info:
  name: Merge Expression Table for RNA-Seq
  version: v0.1.0
  description: Merge expression table for rna-seq.
  category: Tool
  home: https://github.com/yjcyxky/merge-rnaseq-exp
  source: PGx
  short_name: merge-rnaseq-exp
  icons:
    - src: ""
      type: image/png
      sizes: 192x192
  author: Jingcheng Yang
  maintainers:
    - Jingcheng Yang
  tags:
    - Tool
  readme: https://raw.githubusercontent.com/yjcyxky/merge-rnaseq-exp/master/README.md
plugin:
  name: merge-rnaseq-expression
  display-name: Merge RNASeq Expression
  lazy-load: false
init:
  # TService core support four types of initialization step: unpack-env, load-namespace, register-plugin, init-event, init-plugin
  # Unpack environment/configuration/data related file(s) to the conventional directory.
  # You may need to repair the environment after unpacking, if you package your python dependencies as a archive file. so you can use a shell command in PATH to fix some problems, such as repairing python environment, changing the permissions of files in these directory.
  # The envname points to the environment file which is placed in `resource` directory. It may be a tar.gz|zip file or a directory.
  # Several variables will be passed into this yaml file, so you can use template syntax to get them. Such as ENV_DEST_DIR, DATA_DIR, CONFIG_DIR, ENV_NAME.
  - step: unpack-env
    envname: variation-reviewer
    envtype: environment
    postunpack: clone-env /opt/local/merge-rnaseq-expression {{ ENV_DEST_DIR }}
  - step: unpack-env
    envname: templates
    envtype: configuration
  - step: unpack-env
    envname: examples
    envtype: data
  - step: load-namespace
    namespace: tservice.plugins.merge-rnaseq-expression
  - step: register-plugin
    entrypoint: tservice.plugins.merge-rnaseq-expression/metadata
  - step: init-event
    entrypoint: tservice.plugins.merge-rnaseq-expression/events-init
  # The plugin related config will be passed to init-plugin funcion and it is a hashmap.
  # The hashmap should be defined in the config file of main program, and the plugin name must be as a key.
  - step: init-plugin
    entrypoint: tservice.plugins.merge-rnaseq-expression/init-plugin
```


**NOTE: The `tservice-plugin.yaml` must be placed in the resource directory.**

## TODO

[] Add schema definition for `tservice-plugin.yaml`.
[] Add documentation.

## License

Copyright © 2018 Jingcheng Yang

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
