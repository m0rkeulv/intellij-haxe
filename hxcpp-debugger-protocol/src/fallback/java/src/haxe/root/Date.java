// Generated by Haxe 3.4.7
package haxe.root;

import haxe.root.*;

@SuppressWarnings(value={"rawtypes", "unchecked"})
public class Date extends haxe.lang.HxObject
{
	public Date(haxe.lang.EmptyObject empty)
	{
	}
	
	
	public Date(int year, int month, int day, int hour, int min, int sec)
	{
		//line 30 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		haxe.root.Date.__hx_ctor__Date(this, year, month, day, hour, min, sec);
	}
	
	
	public static void __hx_ctor__Date(haxe.root.Date __hx_this, int year, int month, int day, int hour, int min, int sec)
	{
		//line 33 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		if (( year != 0 )) 
		{
			//line 33 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
			year -= 1900;
		}
		else
		{
			//line 33 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
			year = 0;
		}
		
		//line 34 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		__hx_this.date = new java.util.Date(year, month, day, hour, min, sec);
	}
	
	
	public static haxe.root.Date now()
	{
		//line 94 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		haxe.root.Date d = new haxe.root.Date(0, 0, 0, 0, 0, 0);
		//line 95 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		d.date = new java.util.Date();
		//line 96 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		return d;
	}
	
	
	public static haxe.root.Date fromTime(double t)
	{
		//line 101 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		haxe.root.Date d = new haxe.root.Date(0, 0, 0, 0, 0, 0);
		//line 102 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		d.date = new java.util.Date(((long) (t) ));
		//line 103 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		return d;
	}
	
	
	public static haxe.root.Date fromString(java.lang.String s)
	{
		//line 108 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		int _g = s.length();
		//line 108 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		switch (_g)
		{
			case 8:
			{
				//line 111 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				haxe.root.Array<java.lang.String> k = haxe.lang.StringExt.split(s, ":");
				//line 112 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				haxe.root.Date d = new haxe.root.Date(0, 0, 0, ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(k.__get(0)))) ), ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(k.__get(1)))) ), ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(k.__get(2)))) ));
				//line 113 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				return d;
			}
			
			
			case 10:
			{
				//line 115 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				haxe.root.Array<java.lang.String> k1 = haxe.lang.StringExt.split(s, "-");
				//line 116 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				return new haxe.root.Date(((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(k1.__get(0)))) ), ( ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(k1.__get(1)))) ) - ((int) (1) ) ), ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(k1.__get(2)))) ), 0, 0, 0);
			}
			
			
			case 19:
			{
				//line 118 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				haxe.root.Array<java.lang.String> k2 = haxe.lang.StringExt.split(s, " ");
				//line 119 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				haxe.root.Array<java.lang.String> y = haxe.lang.StringExt.split(k2.__get(0), "-");
				//line 120 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				haxe.root.Array<java.lang.String> t = haxe.lang.StringExt.split(k2.__get(1), ":");
				//line 121 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				return new haxe.root.Date(((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(y.__get(0)))) ), ( ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(y.__get(1)))) ) - ((int) (1) ) ), ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(y.__get(2)))) ), ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(t.__get(0)))) ), ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(t.__get(1)))) ), ((int) (haxe.lang.Runtime.toInt(haxe.root.Std.parseInt(t.__get(2)))) ));
			}
			
			
			default:
			{
				//line 123 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				throw haxe.lang.HaxeException.wrap(( "Invalid date format : " + s ));
			}
			
		}
		
	}
	
	
	public java.util.Date date;
	
	@Override public java.lang.Object __hx_setField(java.lang.String field, java.lang.Object value, boolean handleProperties)
	{
		//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		{
			//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
			boolean __temp_executeDef1 = true;
			//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
			switch (field.hashCode())
			{
				case 3076014:
				{
					//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
					if (field.equals("date")) 
					{
						//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
						__temp_executeDef1 = false;
						//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
						this.date = ((java.util.Date) (value) );
						//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
						return value;
					}
					
					//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
					break;
				}
				
				
			}
			
			//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
			if (__temp_executeDef1) 
			{
				//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				return super.__hx_setField(field, value, handleProperties);
			}
			else
			{
				//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				throw null;
			}
			
		}
		
	}
	
	
	@Override public java.lang.Object __hx_getField(java.lang.String field, boolean throwErrors, boolean isCheck, boolean handleProperties)
	{
		//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		{
			//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
			boolean __temp_executeDef1 = true;
			//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
			switch (field.hashCode())
			{
				case 3076014:
				{
					//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
					if (field.equals("date")) 
					{
						//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
						__temp_executeDef1 = false;
						//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
						return this.date;
					}
					
					//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
					break;
				}
				
				
			}
			
			//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
			if (__temp_executeDef1) 
			{
				//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				return super.__hx_getField(field, throwErrors, isCheck, handleProperties);
			}
			else
			{
				//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
				throw null;
			}
			
		}
		
	}
	
	
	@Override public void __hx_getFields(haxe.root.Array<java.lang.String> baseArr)
	{
		//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		baseArr.push("date");
		//line 26 "C:\\HaxeToolkit\\haxe\\std\\java\\_std\\Date.hx"
		super.__hx_getFields(baseArr);
	}
	
	
}


