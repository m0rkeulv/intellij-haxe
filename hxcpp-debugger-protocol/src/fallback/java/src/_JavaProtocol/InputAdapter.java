// Generated by Haxe 3.4.7
package _JavaProtocol;

import haxe.root.*;

@SuppressWarnings(value={"rawtypes", "unchecked"})
public class InputAdapter extends haxe.io.Input
{
	public InputAdapter(haxe.lang.EmptyObject empty)
	{
		//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		super(((haxe.lang.EmptyObject) (haxe.lang.EmptyObject.EMPTY) ));
	}
	
	
	public InputAdapter(java.io.InputStream is)
	{
		//line 203 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		_JavaProtocol.InputAdapter.__hx_ctor__JavaProtocol_InputAdapter(this, is);
	}
	
	
	public static void __hx_ctor__JavaProtocol_InputAdapter(_JavaProtocol.InputAdapter __hx_this, java.io.InputStream is)
	{
		//line 205 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		__hx_this.mIs = is;
	}
	
	
	@Override public int readBytes(haxe.io.Bytes bytes, int pos, int len)
	{
		//line 212 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		try 
		{
			//line 212 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			return this.mIs.read(((byte[]) (bytes.b) ), ((int) (pos) ), ((int) (len) ));
		}
		catch (java.io.IOException e)
		{
			//line 215 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			haxe.lang.Exceptions.setException(e);
			//line 215 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			throw haxe.lang.HaxeException.wrap(( "IOException: " + haxe.root.Std.string(e) ));
		}
		
		
	}
	
	
	public java.io.InputStream mIs;
	
	@Override public java.lang.Object __hx_setField(java.lang.String field, java.lang.Object value, boolean handleProperties)
	{
		//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		{
			//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			boolean __temp_executeDef1 = true;
			//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			switch (field.hashCode())
			{
				case 107127:
				{
					//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					if (field.equals("mIs")) 
					{
						//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						__temp_executeDef1 = false;
						//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						this.mIs = ((java.io.InputStream) (value) );
						//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						return value;
					}
					
					//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					break;
				}
				
				
			}
			
			//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			if (__temp_executeDef1) 
			{
				//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
				return super.__hx_setField(field, value, handleProperties);
			}
			else
			{
				//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
				throw null;
			}
			
		}
		
	}
	
	
	@Override public java.lang.Object __hx_getField(java.lang.String field, boolean throwErrors, boolean isCheck, boolean handleProperties)
	{
		//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		{
			//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			boolean __temp_executeDef1 = true;
			//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			switch (field.hashCode())
			{
				case 107127:
				{
					//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					if (field.equals("mIs")) 
					{
						//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						__temp_executeDef1 = false;
						//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						return this.mIs;
					}
					
					//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					break;
				}
				
				
				case -1140063115:
				{
					//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					if (field.equals("readBytes")) 
					{
						//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						__temp_executeDef1 = false;
						//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						return ((haxe.lang.Function) (new haxe.lang.Closure(this, "readBytes")) );
					}
					
					//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					break;
				}
				
				
			}
			
			//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			if (__temp_executeDef1) 
			{
				//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
				return super.__hx_getField(field, throwErrors, isCheck, handleProperties);
			}
			else
			{
				//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
				throw null;
			}
			
		}
		
	}
	
	
	@Override public void __hx_getFields(haxe.root.Array<java.lang.String> baseArr)
	{
		//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		baseArr.push("mIs");
		//line 201 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		super.__hx_getFields(baseArr);
	}
	
	
}

