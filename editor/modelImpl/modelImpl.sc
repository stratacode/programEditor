package sc.editor;

public editor.modelImpl extends model, sys.layeredSystem {
   codeType = CodeType.Application;

   liveDynamicTypes = layeredSystem.options.editEditor;
   hidden = !layeredSystem.options.editEditor;

   // The modelImpl layer won't be included in the js runtime due to inheriting
   // the constraint from sys.layeredSystem. Because this layer exposes remote
   // methods, we want layers to extend from it but not inherit the runtime
   // constraint.
   exportRuntime = false;
   exportProcess = false;
}
