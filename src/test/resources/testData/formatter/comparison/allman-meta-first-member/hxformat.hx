package openfl.display._internal;

#if !flash
import openfl.display.BitmapData;
import openfl.filters.BitmapFilterShader;

#if !openfl_debug
@:fileXml('tags="haxe,release"')
@:noDebug
#end
@:access(openfl.display.BitmapData)
@SuppressWarnings("checkstyle:FieldDocComment")
class BlendModeShader extends BitmapFilterShader
{
	@:glFragmentSource("varying vec2 openfl_TextureCoordv;
		uniform sampler2D openfl_Texture;
		void main(void) {
			gl_FragColor = texture2D(openfl_Texture, openfl_TextureCoordv);
		}")
	public function new()
	{
		super();
	}
}
#end

class Plain
{
	@:keep
	public var value = 1;
}
