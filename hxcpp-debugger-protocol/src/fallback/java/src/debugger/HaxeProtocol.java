// Generated by Haxe 3.4.7
package debugger;

import haxe.root.*;

@SuppressWarnings(value={"rawtypes", "unchecked"})
public class HaxeProtocol extends haxe.lang.HxObject
{
	static
	{
		//line 134 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		debugger.HaxeProtocol.gClientIdentification = "Haxe debug client v1.1 coming at you!\n\n";
		//line 136 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		debugger.HaxeProtocol.gServerIdentification = "Haxe debug server v1.1 ready and willing, sir!\n\n";
	}
	
	public HaxeProtocol(haxe.lang.EmptyObject empty)
	{
	}
	
	
	public HaxeProtocol()
	{
		//line 28 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		debugger.HaxeProtocol.__hx_ctor_debugger_HaxeProtocol(this);
	}
	
	
	public static void __hx_ctor_debugger_HaxeProtocol(debugger.HaxeProtocol __hx_this)
	{
	}
	
	
	public static void writeClientIdentification(haxe.io.Output output)
	{
		//line 32 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		output.writeString(debugger.HaxeProtocol.gClientIdentification);
	}
	
	
	public static void writeServerIdentification(haxe.io.Output output)
	{
		//line 37 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		output.writeString(debugger.HaxeProtocol.gServerIdentification);
	}
	
	
	public static void readClientIdentification(haxe.io.Input input)
	{
		//line 42 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		haxe.io.Bytes id = input.read(debugger.HaxeProtocol.gClientIdentification.length());
		//line 43 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		if ( ! (haxe.lang.Runtime.valEq(id.toString(), debugger.HaxeProtocol.gClientIdentification)) ) 
		{
			//line 44 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			throw haxe.lang.HaxeException.wrap(( "Unexpected client identification string: " + haxe.root.Std.string(id) ));
		}
		
	}
	
	
	public static void readServerIdentification(haxe.io.Input input)
	{
		//line 50 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		haxe.io.Bytes id = input.read(debugger.HaxeProtocol.gServerIdentification.length());
		//line 51 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		if ( ! (haxe.lang.Runtime.valEq(id.toString(), debugger.HaxeProtocol.gServerIdentification)) ) 
		{
			//line 52 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			throw haxe.lang.HaxeException.wrap(( "Unexpected server identification string: " + haxe.root.Std.string(id) ));
		}
		
	}
	
	
	public static void writeCommand(haxe.io.Output output, debugger.Command command)
	{
		//line 59 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		debugger.HaxeProtocol.writeDynamic(output, command);
	}
	
	
	public static void writeMessage(haxe.io.Output output, debugger.Message message)
	{
		//line 65 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		debugger.HaxeProtocol.writeDynamic(output, message);
	}
	
	
	public static debugger.Command readCommand(haxe.io.Input input)
	{
		//line 70 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		java.lang.Object raw = debugger.HaxeProtocol.readDynamic(input);
		//line 74 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		try 
		{
			//line 74 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			return ((debugger.Command) (raw) );
		}
		catch (java.lang.Throwable __temp_catchallException1)
		{
			//line 74 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			haxe.lang.Exceptions.setException(__temp_catchallException1);
			//line 77 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			java.lang.Object __temp_catchall2 = __temp_catchallException1;
			//line 77 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			if (( __temp_catchall2 instanceof haxe.lang.HaxeException )) 
			{
				//line 77 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				__temp_catchall2 = ((haxe.lang.HaxeException) (__temp_catchallException1) ).obj;
			}
			
			//line 77 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			{
				//line 77 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				java.lang.Object e = __temp_catchall2;
				//line 77 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				throw haxe.lang.HaxeException.wrap(( ( ( "Expected Command, but got " + haxe.root.Std.string(raw) ) + ": " ) + haxe.root.Std.string(e) ));
			}
			
		}
		
		
	}
	
	
	public static debugger.Message readMessage(haxe.io.Input input)
	{
		//line 83 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		java.lang.Object raw = debugger.HaxeProtocol.readDynamic(input);
		//line 87 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		try 
		{
			//line 87 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			return ((debugger.Message) (raw) );
		}
		catch (java.lang.Throwable __temp_catchallException1)
		{
			//line 87 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			haxe.lang.Exceptions.setException(__temp_catchallException1);
			//line 90 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			java.lang.Object __temp_catchall2 = __temp_catchallException1;
			//line 90 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			if (( __temp_catchall2 instanceof haxe.lang.HaxeException )) 
			{
				//line 90 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				__temp_catchall2 = ((haxe.lang.HaxeException) (__temp_catchallException1) ).obj;
			}
			
			//line 90 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			{
				//line 90 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				java.lang.Object e = __temp_catchall2;
				//line 90 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				throw haxe.lang.HaxeException.wrap(( ( ( "Expected Message, but got " + haxe.root.Std.string(raw) ) + ": " ) + haxe.root.Std.string(e) ));
			}
			
		}
		
		
	}
	
	
	public static void writeDynamic(haxe.io.Output output, java.lang.Object value)
	{
		//line 98 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		java.lang.String string = haxe.Serializer.run(value);
		//line 101 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		int msg_len = string.length();
		//line 102 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		haxe.io.Bytes msg_len_raw = haxe.io.Bytes.alloc(8);
		//line 104 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		{
			//line 104 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			int _g = 0;
			//line 104 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			while (( _g < 8 ))
			{
				//line 104 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				int i = _g++;
				//line 105 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				msg_len_raw.b[( 7 - i )] = ((byte) (( ( msg_len % 10 ) + 48 )) );
				//line 106 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				msg_len = ((int) (( msg_len / 10 )) );
			}
			
		}
		
		//line 109 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		output.write(msg_len_raw);
		//line 110 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		output.writeString(string);
	}
	
	
	public static java.lang.Object readDynamic(haxe.io.Input input)
	{
		//line 115 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		haxe.io.Bytes msg_len_raw = input.read(8);
		//line 118 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		int msg_len = 0;
		//line 119 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		{
			//line 119 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			int _g = 0;
			//line 119 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			while (( _g < 8 ))
			{
				//line 119 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				int i = _g++;
				//line 120 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				msg_len *= 10;
				//line 121 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
				msg_len += ( (( msg_len_raw.b[i] & 255 )) - 48 );
			}
			
		}
		
		//line 126 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		if (( msg_len > 2097152 )) 
		{
			//line 127 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
			throw haxe.lang.HaxeException.wrap(( ( "Read bad message length: " + msg_len ) + "." ));
		}
		
		//line 131 "C:\\HaxeToolkit\\haxe\\lib\\hxcpp-debugger\\git\\debugger\\HaxeProtocol.hx"
		return haxe.Unserializer.run(input.read(msg_len).toString());
	}
	
	
	public static java.lang.String gClientIdentification;
	
	public static java.lang.String gServerIdentification;
	
}


