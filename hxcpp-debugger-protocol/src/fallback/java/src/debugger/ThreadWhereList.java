// Generated by Haxe 3.4.7
package debugger;

import haxe.root.*;

@SuppressWarnings(value={"rawtypes", "unchecked"})
public class ThreadWhereList extends haxe.lang.ParamEnum
{
	public ThreadWhereList(int index, java.lang.Object[] params)
	{
		//line 99 "C:\\HaxeToolkit\\haxe\\std\\java\\internal\\HxObject.hx"
		super(index, params);
	}
	
	
	public static final java.lang.String[] __hx_constructs = new java.lang.String[]{"Terminator", "Where"};
	
	public static final debugger.ThreadWhereList Terminator = new debugger.ThreadWhereList(0, null);
	
	public static debugger.ThreadWhereList Where(int number, debugger.ThreadStatus status, debugger.FrameList frameList, debugger.ThreadWhereList next)
	{
		//line 260 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\IController.hx"
		return new debugger.ThreadWhereList(1, new java.lang.Object[]{number, status, frameList, next});
	}
	
	
	@Override public java.lang.String getTag()
	{
		//line 257 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\IController.hx"
		return debugger.ThreadWhereList.__hx_constructs[this.index];
	}
	
	
}


