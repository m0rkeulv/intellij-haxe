// Generated by Haxe 3.4.7
package haxe.ds;

import haxe.root.*;

@SuppressWarnings(value={"rawtypes", "unchecked"})
public class StringMap_keys_374__Fun<T> extends haxe.lang.Function
{
	public StringMap_keys_374__Fun(int[] i, haxe.ds.StringMap<T> _gthis)
	{
		//line 374 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\haxe\\ds\\StringMap.hx"
		super(0, 0);
		//line 374 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\haxe\\ds\\StringMap.hx"
		this.i = i;
		//line 374 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\haxe\\ds\\StringMap.hx"
		this._gthis = _gthis;
	}
	
	
	@Override public java.lang.Object __hx_invoke0_o()
	{
		//line 375 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\haxe\\ds\\StringMap.hx"
		java.lang.String ret = this._gthis._keys[this.i[0]];
		//line 376 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\haxe\\ds\\StringMap.hx"
		this._gthis.cachedIndex = this.i[0];
		//line 377 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\haxe\\ds\\StringMap.hx"
		this._gthis.cachedKey = ret;
		//line 379 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\haxe\\ds\\StringMap.hx"
		this.i[0] += 1;
		//line 380 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\haxe\\ds\\StringMap.hx"
		return ret;
	}
	
	
	public int[] i;
	
	public haxe.ds.StringMap<T> _gthis;
	
}


