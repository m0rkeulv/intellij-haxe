// Generated by Haxe 3.4.7
package _JavaProtocol;

import haxe.root.*;

@SuppressWarnings(value={"rawtypes", "unchecked"})
public class OutputAdapter extends haxe.io.Output
{
	public OutputAdapter(haxe.lang.EmptyObject empty)
	{
		//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		super(((haxe.lang.EmptyObject) (haxe.lang.EmptyObject.EMPTY) ));
	}
	
	
	public OutputAdapter(java.io.OutputStream os)
	{
		//line 180 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		_JavaProtocol.OutputAdapter.__hx_ctor__JavaProtocol_OutputAdapter(this, os);
	}
	
	
	public static void __hx_ctor__JavaProtocol_OutputAdapter(_JavaProtocol.OutputAdapter __hx_this, java.io.OutputStream os)
	{
		//line 182 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		__hx_this.mOs = os;
	}
	
	
	@Override public int writeBytes(haxe.io.Bytes bytes, int pos, int len)
	{
		//line 188 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		try 
		{
			//line 189 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			this.mOs.write(((byte[]) (bytes.b) ), ((int) (pos) ), ((int) (len) ));
			//line 190 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			return len;
		}
		catch (java.io.IOException e)
		{
			//line 193 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			haxe.lang.Exceptions.setException(e);
			//line 193 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			throw haxe.lang.HaxeException.wrap(( "IOException: " + haxe.root.Std.string(e) ));
		}
		
		
	}
	
	
	public java.io.OutputStream mOs;
	
	@Override public java.lang.Object __hx_setField(java.lang.String field, java.lang.Object value, boolean handleProperties)
	{
		//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		{
			//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			boolean __temp_executeDef1 = true;
			//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			switch (field.hashCode())
			{
				case 107313:
				{
					//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					if (field.equals("mOs")) 
					{
						//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						__temp_executeDef1 = false;
						//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						this.mOs = ((java.io.OutputStream) (value) );
						//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						return value;
					}
					
					//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					break;
				}
				
				
			}
			
			//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			if (__temp_executeDef1) 
			{
				//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
				return super.__hx_setField(field, value, handleProperties);
			}
			else
			{
				//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
				throw null;
			}
			
		}
		
	}
	
	
	@Override public java.lang.Object __hx_getField(java.lang.String field, boolean throwErrors, boolean isCheck, boolean handleProperties)
	{
		//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		{
			//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			boolean __temp_executeDef1 = true;
			//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			switch (field.hashCode())
			{
				case 107313:
				{
					//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					if (field.equals("mOs")) 
					{
						//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						__temp_executeDef1 = false;
						//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						return this.mOs;
					}
					
					//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					break;
				}
				
				
				case -662729780:
				{
					//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					if (field.equals("writeBytes")) 
					{
						//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						__temp_executeDef1 = false;
						//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
						return ((haxe.lang.Function) (new haxe.lang.Closure(this, "writeBytes")) );
					}
					
					//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
					break;
				}
				
				
			}
			
			//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
			if (__temp_executeDef1) 
			{
				//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
				return super.__hx_getField(field, throwErrors, isCheck, handleProperties);
			}
			else
			{
				//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
				throw null;
			}
			
		}
		
	}
	
	
	@Override public void __hx_getFields(haxe.root.Array<java.lang.String> baseArr)
	{
		//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		baseArr.push("mOs");
		//line 178 "P:\\Workspaces\\haxe-gradle-clean\\slefcheck\\intellij-haxe\\hxcpp-debugger-protocol\\src\\main\\haxe\\JavaProtocol.hx"
		super.__hx_getFields(baseArr);
	}
	
	
}


