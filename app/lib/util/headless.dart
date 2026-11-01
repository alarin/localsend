/// True when the current engine runs the headless direct-share entrypoint
/// (no widgets, no Routerino context). UI-triggering code paths must check
/// this flag and fall back to silent behavior.
bool isHeadlessDirectShare = false;
