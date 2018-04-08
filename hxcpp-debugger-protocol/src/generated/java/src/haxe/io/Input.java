// Generated by Haxe 3.4.7
package haxe.io;

import haxe.root.*;

@SuppressWarnings(value={"rawtypes", "unchecked"})
public class Input extends haxe.lang.HxObject
{
	public Input(haxe.lang.EmptyObject empty)
	{
	}
	
	
	public Input()
	{
		//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		haxe.io.Input.__hx_ctor_haxe_io_Input(this);
	}
	
	
	public static void __hx_ctor_haxe_io_Input(haxe.io.Input __hx_this)
	{
	}
	
	
	public int readByte()
	{
		//line 53 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		throw haxe.lang.HaxeException.wrap("Not implemented");
	}
	
	
	public int readBytes(haxe.io.Bytes s, int pos, int len)
	{
		//line 65 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		int k = len;
		//line 66 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		byte[] b = s.b;
		//line 67 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		if (( ( ( pos < 0 ) || ( len < 0 ) ) || ( ( pos + len ) > s.length ) )) 
		{
			//line 68 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			throw haxe.lang.HaxeException.wrap(haxe.io.Error.OutsideBounds);
		}
		
		//line 70 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		try 
		{
			//line 70 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			while (( k > 0 ))
			{
				//line 78 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				b[pos] = ((byte) (this.readByte()) );
				//line 80 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				 ++ pos;
				//line 81 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				 -- k;
			}
			
		}
		catch (java.lang.Throwable __temp_catchallException1)
		{
			//line 70 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			haxe.lang.Exceptions.setException(__temp_catchallException1);
			//line 83 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			java.lang.Object __temp_catchall2 = __temp_catchallException1;
			//line 83 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			if (( __temp_catchall2 instanceof haxe.lang.HaxeException )) 
			{
				//line 83 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				__temp_catchall2 = ((haxe.lang.HaxeException) (__temp_catchallException1) ).obj;
			}
			
			//line 83 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			if (( __temp_catchall2 instanceof haxe.io.Eof )) 
			{
				//line 83 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				haxe.io.Eof eof = ((haxe.io.Eof) (__temp_catchall2) );
				//line 83 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				{
				}
				
			}
			else
			{
				//line 83 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				throw haxe.lang.HaxeException.wrap(__temp_catchallException1);
			}
			
		}
		
		
		//line 84 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		return ( len - k );
	}
	
	
	public haxe.io.Bytes read(int nbytes)
	{
		//line 148 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		haxe.io.Bytes s = haxe.io.Bytes.alloc(nbytes);
		//line 149 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		int p = 0;
		//line 150 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		while (( nbytes > 0 ))
		{
			//line 151 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			int k = this.readBytes(s, p, nbytes);
			//line 152 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			if (( k == 0 )) 
			{
				//line 152 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				throw haxe.lang.HaxeException.wrap(haxe.io.Error.Blocked);
			}
			
			//line 153 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			p += k;
			//line 154 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			nbytes -= k;
		}
		
		//line 156 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		return s;
	}
	
	
	public java.lang.String readLine()
	{
		//line 178 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		haxe.io.BytesBuffer buf = new haxe.io.BytesBuffer();
		//line 179 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		int last = 0;
		//line 180 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		java.lang.String s = null;
		//line 181 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		try 
		{
			//line 182 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			while (true)
			{
				//line 182 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				last = this.readByte();
				//line 182 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				if ( ! ((( last != 10 ))) ) 
				{
					//line 182 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					break;
				}
				
				//line 183 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				buf.b.write(((int) (last) ));
			}
			
			//line 184 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			s = buf.getBytes().toString();
			//line 185 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			if (haxe.lang.Runtime.eq(haxe.lang.StringExt.charCodeAt(s, ( s.length() - 1 )), 13)) 
			{
				//line 185 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				s = haxe.lang.StringExt.substr(s, 0, -1);
			}
			
		}
		catch (java.lang.Throwable __temp_catchallException1)
		{
			//line 181 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			haxe.lang.Exceptions.setException(__temp_catchallException1);
			//line 186 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			java.lang.Object __temp_catchall2 = __temp_catchallException1;
			//line 186 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			if (( __temp_catchall2 instanceof haxe.lang.HaxeException )) 
			{
				//line 186 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				__temp_catchall2 = ((haxe.lang.HaxeException) (__temp_catchallException1) ).obj;
			}
			
			//line 186 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			if (( __temp_catchall2 instanceof haxe.io.Eof )) 
			{
				//line 186 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				haxe.io.Eof e = ((haxe.io.Eof) (__temp_catchall2) );
				//line 186 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				{
					//line 187 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					s = buf.getBytes().toString();
					//line 188 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					if (( s.length() == 0 )) 
					{
						//line 189 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						throw haxe.lang.HaxeException.wrap(e);
					}
					
				}
				
			}
			else
			{
				//line 186 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				throw haxe.lang.HaxeException.wrap(__temp_catchallException1);
			}
			
		}
		
		
		//line 191 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		return s;
	}
	
	
	@Override public java.lang.Object __hx_getField(java.lang.String field, boolean throwErrors, boolean isCheck, boolean handleProperties)
	{
		//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		{
			//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			boolean __temp_executeDef1 = true;
			//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			switch (field.hashCode())
			{
				case -867777878:
				{
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					if (field.equals("readLine")) 
					{
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						__temp_executeDef1 = false;
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						return ((haxe.lang.Function) (new haxe.lang.Closure(this, "readLine")) );
					}
					
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					break;
				}
				
				
				case -868060226:
				{
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					if (field.equals("readByte")) 
					{
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						__temp_executeDef1 = false;
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						return ((haxe.lang.Function) (new haxe.lang.Closure(this, "readByte")) );
					}
					
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					break;
				}
				
				
				case 3496342:
				{
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					if (field.equals("read")) 
					{
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						__temp_executeDef1 = false;
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						return ((haxe.lang.Function) (new haxe.lang.Closure(this, "read")) );
					}
					
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					break;
				}
				
				
				case -1140063115:
				{
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					if (field.equals("readBytes")) 
					{
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						__temp_executeDef1 = false;
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						return ((haxe.lang.Function) (new haxe.lang.Closure(this, "readBytes")) );
					}
					
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					break;
				}
				
				
			}
			
			//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			if (__temp_executeDef1) 
			{
				//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				return super.__hx_getField(field, throwErrors, isCheck, handleProperties);
			}
			else
			{
				//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				throw null;
			}
			
		}
		
	}
	
	
	@Override public java.lang.Object __hx_invokeField(java.lang.String field, haxe.root.Array dynargs)
	{
		//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
		{
			//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			boolean __temp_executeDef1 = true;
			//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			switch (field.hashCode())
			{
				case -867777878:
				{
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					if (field.equals("readLine")) 
					{
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						__temp_executeDef1 = false;
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						return this.readLine();
					}
					
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					break;
				}
				
				
				case -868060226:
				{
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					if (field.equals("readByte")) 
					{
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						__temp_executeDef1 = false;
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						return this.readByte();
					}
					
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					break;
				}
				
				
				case 3496342:
				{
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					if (field.equals("read")) 
					{
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						__temp_executeDef1 = false;
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						return this.read(((int) (haxe.lang.Runtime.toInt(dynargs.__get(0))) ));
					}
					
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					break;
				}
				
				
				case -1140063115:
				{
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					if (field.equals("readBytes")) 
					{
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						__temp_executeDef1 = false;
						//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
						return this.readBytes(((haxe.io.Bytes) (dynargs.__get(0)) ), ((int) (haxe.lang.Runtime.toInt(dynargs.__get(1))) ), ((int) (haxe.lang.Runtime.toInt(dynargs.__get(2))) ));
					}
					
					//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
					break;
				}
				
				
			}
			
			//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
			if (__temp_executeDef1) 
			{
				//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				return super.__hx_invokeField(field, dynargs);
			}
			else
			{
				//line 31 "C:\\HaxeToolkit\\haxe\\std\\haxe\\io\\Input.hx"
				throw null;
			}
			
		}
		
	}
	
	
}


