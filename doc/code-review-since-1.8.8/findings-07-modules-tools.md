# Chunk 9 — display-protocol, common, jps-plugin, tools (~40 files)

- **display-protocol** (22): rules-era, reviewed twice this session
  (BlueprintCache deletion, DisplayJson structural fixes). DTOs/records/
  enums conform; client + transport clean. Done.
- **common SDK data** (`HaxeSdkAdditionalDataBase` + impl): clean legacy
  bean, BUT carries the `useCompilerCompletionFlag` /
  `removeCompletionDuplicatesFlag` pair — the same possibly-dead settings
  flagged in chunk 2 (their consumer, HaxeCompilerCompletionContributor, was
  deleted on this branch). The dead-setting investigation spans:
  common interface + impl, jps `JpsHaxeSdkAdditionalDataImpl`, the SDK
  settings panel, and `HaxeSdkData`. One [discuss] item, five files.
- **jps-plugin** (`HaxeModuleLevelBuilder`, `JpsHaxeSdkAdditionalDataImpl`):
  V1 external-build machinery — candidates to fall with the V1 removal
  (task #31); not worth polishing before that decision. [discuss: keep or
  delete with V1].
- **tools/LimeProjectParser** (7 .hx): rules-era tool (task #32) with its
  own tests + README; 4.3-level Haxe per its build. Conforms at sweep level.

Chunk 9 complete.
