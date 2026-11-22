// Loading/unloading test only; no firmware calls, devices, or card access.
__attribute__((constructor,used)) int module_initialize(void) { return 0; }
__attribute__((destructor,used)) static void module_uninitialize(void) { }
