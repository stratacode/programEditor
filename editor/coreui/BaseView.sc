import sc.layer.Layer;
import sc.dyn.DynUtil;
import sc.type.CTypeUtil;

import sc.bind.Bind;
import sc.bind.AbstractListener;
import sc.bind.IListener;

import java.util.Iterator;

/** This is the base class for other views which involve a code model */
abstract class BaseView {
   EditorModel editorModel;

   @sc.obj.Sync
   boolean viewVisible;

   editorModel =: invalidateModel();
   viewVisible =: invalidateModel();

   JavaModel currentJavaModel := editorModel.currentJavaModel;
   currentJavaModel =: invalidateModel();

   void invalidateModel() {
   }
}