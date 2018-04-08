// Generated by Haxe 3.4.7
package haxe.root;

import haxe.root.*;

@SuppressWarnings(value={"rawtypes", "unchecked"})
public class StringTools extends haxe.lang.HxObject
{
	static
	{
		//line 506 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		haxe.root.StringTools.winMetaCharacters = new haxe.root.Array<java.lang.Object>(new java.lang.Object[]{32, 40, 41, 37, 33, 94, 34, 60, 62, 38, 124, 10, 13, 44, 59});
	}
	
	public StringTools(haxe.lang.EmptyObject empty)
	{
	}
	
	
	public StringTools()
	{
		//line 33 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		haxe.root.StringTools.__hx_ctor__StringTools(this);
	}
	
	
	public static void __hx_ctor__StringTools(haxe.root.StringTools __hx_this)
	{
	}
	
	
	public static java.lang.String urlEncode(java.lang.String s)
	{
		//line 47 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		try 
		{
			//line 47 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			return haxe.root.StringTools.postProcessUrlEncode(java.net.URLEncoder.encode(haxe.lang.Runtime.toString(s), haxe.lang.Runtime.toString("UTF-8")));
		}
		catch (java.lang.Throwable typedException)
		{
			//line 37 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			throw haxe.lang.HaxeException.wrap(typedException);
		}
		
		
	}
	
	
	public static java.lang.String postProcessUrlEncode(java.lang.String s)
	{
		//line 70 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		haxe.root.StringBuf ret = new haxe.root.StringBuf();
		//line 71 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		int i = 0;
		//line 71 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		int len = s.length();
		//line 73 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		while (( i < len ))
		{
			//line 74 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			char _g = s.charAt(i++);
			//line 74 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			{
				//line 74 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				char __temp_switch1 = (_g);
				//line 77 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				if (( __temp_switch1 == 37 )) 
				{
					//line 77 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
					if (( i <= ( len - 2 ) )) 
					{
						//line 78 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						char c1 = s.charAt(i++);
						//line 78 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						char c2 = s.charAt(i++);
						//line 80 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						{
							//line 80 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
							char __temp_switch2 = (c1);
							//line 80 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
							if (( __temp_switch2 == 50 )) 
							{
								//line 80 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
								{
									//line 80 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									char __temp_switch4 = (c2);
									//line 82 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									if (( __temp_switch4 == 49 )) 
									{
										//line 82 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
										ret.addChar(33);
									}
									else
									{
										//line 84 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
										if (( __temp_switch4 == 55 )) 
										{
											//line 84 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
											ret.addChar(39);
										}
										else
										{
											//line 86 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
											if (( __temp_switch4 == 56 )) 
											{
												//line 86 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
												ret.addChar(40);
											}
											else
											{
												//line 88 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
												if (( __temp_switch4 == 57 )) 
												{
													//line 88 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
													ret.addChar(41);
												}
												else
												{
													//line 92 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
													ret.addChar(37);
													//line 93 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
													ret.addChar(((int) (c1) ));
													//line 94 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
													ret.addChar(((int) (c2) ));
												}
												
											}
											
										}
										
									}
									
								}
								
							}
							else
							{
								//line 80 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
								if (( __temp_switch2 == 55 )) 
								{
									//line 80 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									{
										//line 80 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
										char __temp_switch3 = (c2);
										//line 90 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
										if (( ( __temp_switch3 == 69 ) || ( __temp_switch3 == 101 ) )) 
										{
											//line 90 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
											ret.addChar(126);
										}
										else
										{
											//line 92 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
											ret.addChar(37);
											//line 93 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
											ret.addChar(((int) (c1) ));
											//line 94 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
											ret.addChar(((int) (c2) ));
										}
										
									}
									
								}
								else
								{
									//line 92 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									ret.addChar(37);
									//line 93 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									ret.addChar(((int) (c1) ));
									//line 94 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									ret.addChar(((int) (c2) ));
								}
								
							}
							
						}
						
					}
					else
					{
						//line 96 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						char chr = _g;
						//line 97 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						ret.addChar(((int) (chr) ));
					}
					
				}
				else
				{
					//line 76 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
					if (( __temp_switch1 == 43 )) 
					{
						//line 76 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						ret.add(haxe.lang.Runtime.toString("%20"));
					}
					else
					{
						//line 96 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						char chr1 = _g;
						//line 97 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						ret.addChar(((int) (chr1) ));
					}
					
				}
				
			}
			
		}
		
		//line 100 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		return ret.toString();
	}
	
	
	public static java.lang.String urlDecode(java.lang.String s)
	{
		//line 118 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		try 
		{
			//line 118 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			return java.net.URLDecoder.decode(s, "UTF-8");
		}
		catch (java.lang.Throwable __temp_catchallException1)
		{
			//line 118 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			haxe.lang.Exceptions.setException(__temp_catchallException1);
			//line 119 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			java.lang.Object __temp_catchall2 = __temp_catchallException1;
			//line 119 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			if (( __temp_catchall2 instanceof haxe.lang.HaxeException )) 
			{
				//line 119 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				__temp_catchall2 = ((haxe.lang.HaxeException) (__temp_catchallException1) ).obj;
			}
			
			//line 119 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			{
				//line 119 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				java.lang.Object e = __temp_catchall2;
				//line 119 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				throw haxe.lang.HaxeException.wrap(e);
			}
			
		}
		
		
	}
	
	
	public static boolean isSpace(java.lang.String s, int pos)
	{
		//line 249 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		java.lang.Object c = haxe.lang.StringExt.charCodeAt(s, pos);
		//line 250 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		if ( ! ((( ( haxe.lang.Runtime.compare(c, 8) > 0 ) && ( haxe.lang.Runtime.compare(c, 14) < 0 ) ))) ) 
		{
			//line 250 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			return haxe.lang.Runtime.eq(c, 32);
		}
		else
		{
			//line 250 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			return true;
		}
		
	}
	
	
	public static java.lang.String ltrim(java.lang.String s)
	{
		//line 266 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		int l = s.length();
		//line 267 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		int r = 0;
		//line 268 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		while (( ( r < l ) && haxe.root.StringTools.isSpace(s, r) ))
		{
			//line 269 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			 ++ r;
		}
		
		//line 271 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		if (( r > 0 )) 
		{
			//line 272 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			return haxe.lang.StringExt.substr(s, r, ( l - r ));
		}
		else
		{
			//line 274 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			return s;
		}
		
	}
	
	
	public static haxe.root.Array<java.lang.Object> winMetaCharacters;
	
	public static java.lang.String quoteWinArg(java.lang.String argument, boolean escapeMetaCharacters)
	{
		//line 523 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		if ( ! (new haxe.root.EReg("^[^ \t\\\\\"]+$", "").match(argument)) ) 
		{
			//line 528 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			haxe.root.StringBuf result = new haxe.root.StringBuf();
			//line 529 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			boolean needquote = ( ( ( haxe.lang.StringExt.indexOf(argument, " ", null) != -1 ) || ( haxe.lang.StringExt.indexOf(argument, "\t", null) != -1 ) ) || haxe.lang.Runtime.valEq(argument, "") );
			//line 531 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			if (needquote) 
			{
				//line 532 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				result.add(haxe.lang.Runtime.toString("\""));
			}
			
			//line 534 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			haxe.root.StringBuf bs_buf = new haxe.root.StringBuf();
			//line 535 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			{
				//line 535 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				int _g1 = 0;
				//line 535 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				int _g = argument.length();
				//line 535 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				while (( _g1 < _g ))
				{
					//line 535 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
					int i = _g1++;
					//line 536 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
					{
						//line 536 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						java.lang.Object _g2 = haxe.lang.StringExt.charCodeAt(argument, i);
						//line 536 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						if (haxe.lang.Runtime.eq(_g2, null)) 
						{
							//line 547 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
							java.lang.Object c = _g2;
							//line 547 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
							{
								//line 549 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
								if (( bs_buf.b.length() > 0 )) 
								{
									//line 550 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									result.add(haxe.lang.Runtime.toString(bs_buf.toString()));
									//line 551 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									bs_buf = new haxe.root.StringBuf();
								}
								
								//line 553 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
								result.addChar(((int) (haxe.lang.Runtime.toInt(c)) ));
							}
							
						}
						else
						{
							//line 536 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
							switch (((int) (haxe.lang.Runtime.toInt((_g2))) ))
							{
								case 34:
								{
									//line 542 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									java.lang.String bs = bs_buf.toString();
									//line 543 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									result.add(haxe.lang.Runtime.toString(bs));
									//line 544 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									result.add(haxe.lang.Runtime.toString(bs));
									//line 545 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									bs_buf = new haxe.root.StringBuf();
									//line 546 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									result.add(haxe.lang.Runtime.toString("\\\""));
									//line 540 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									break;
								}
								
								
								case 92:
								{
									//line 539 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									bs_buf.add(haxe.lang.Runtime.toString("\\"));
									//line 539 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									break;
								}
								
								
								default:
								{
									//line 547 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									java.lang.Object c1 = _g2;
									//line 547 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									{
										//line 549 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
										if (( bs_buf.b.length() > 0 )) 
										{
											//line 550 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
											result.add(haxe.lang.Runtime.toString(bs_buf.toString()));
											//line 551 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
											bs_buf = new haxe.root.StringBuf();
										}
										
										//line 553 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
										result.addChar(((int) (haxe.lang.Runtime.toInt(c1)) ));
									}
									
									//line 547 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
									break;
								}
								
							}
							
						}
						
					}
					
				}
				
			}
			
			//line 558 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			result.add(haxe.lang.Runtime.toString(bs_buf.toString()));
			//line 560 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			if (needquote) 
			{
				//line 561 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				result.add(haxe.lang.Runtime.toString(bs_buf.toString()));
				//line 562 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				result.add(haxe.lang.Runtime.toString("\""));
			}
			
			//line 565 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			argument = result.toString();
		}
		
		//line 568 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
		if (escapeMetaCharacters) 
		{
			//line 569 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			haxe.root.StringBuf result1 = new haxe.root.StringBuf();
			//line 570 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			{
				//line 570 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				int _g11 = 0;
				//line 570 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				int _g3 = argument.length();
				//line 570 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
				while (( _g11 < _g3 ))
				{
					//line 570 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
					int i1 = _g11++;
					//line 571 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
					java.lang.Object c2 = haxe.lang.StringExt.charCodeAt(argument, i1);
					//line 572 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
					if (( haxe.root.StringTools.winMetaCharacters.indexOf(c2, null) >= 0 )) 
					{
						//line 573 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
						result1.addChar(94);
					}
					
					//line 575 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
					result1.addChar(((int) (haxe.lang.Runtime.toInt(c2)) ));
				}
				
			}
			
			//line 577 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			return result1.toString();
		}
		else
		{
			//line 579 "C:\\HaxeToolkit\\haxe\\std\\StringTools.hx"
			return argument;
		}
		
	}
	
	
}

