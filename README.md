# xeocli

This is __Experimental__ CLI tool for [xeoServices](https//docs.xeo.vision)
It aims to help exploring and testing xeoServices.
The project is __WORK_IN_PROGRESS__ without guranty it will become official part of xeoServices family.
Use it on your own risk.

# 1. Install xeoCLI

## Linux

```bash
$ bash < <(curl -s https://raw.githubusercontent.com/Creoox/xeocli/refs/heads/main/install.sh)
```

## MacOS

## Windows
TBD


# 2. Usage

```bash
xeo --help
Manage your 3D models with xeoVision services - https://docs.xeo.vision

Usage: xeo <subcommand> <file-path or url> <options>
Most subcommands support the options:
  -t, --type             <ifc-xkt | glb-xkt>, Type of conversion
  -f, --file              Path or url to file
  -w, --wait       true  Wait until conversion is finished, if false returns immediately with processId
  -l, --log        false Dump logs to: `<current-dir>/<file-name>_processed/logs/*.log`
  -a, --artifact   false Download all process artifacts (logs, db, glb, ...)
  -o, --output-dir .     Output directory


Subcommands:

  convert    Louch the conversion pipeline.
  validate   Louch the validation pipeline*.

  help       Print this help message.

Examples:
  xeo convert wall.ifc --type ifc-xkt # local file conversion, opens the viewer in the default browser
  xeo convert wall.ifc --type ifc-xkt --log --artifact --json  # drops logs and artifacts, prints the response as JSON
  xeo convert https://raw.githubusercontent.com/xeokit/xeokit-sdk/master/assets/models/ifc/Duplex.ifc --type ifc-xkt --airtifact # conversion from url
  xeo validate wall.ifc
  xeo validate wall.ifc --type ifc-ids-validate
  xeo validate https://raw.githubusercontent.com/xeokit/xeokit-sdk/master/assets/models/ifc/Duplex.ifc --type ifc-model-check --log --artifact
```
