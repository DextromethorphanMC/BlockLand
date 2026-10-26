// Ported from Bliss (Chocapic13' Shaders edit) by Xonk, based on Chocapic13's shaders; see
// assets/blockland/shaders/include/bliss/LICENSE.md. Not BlockLand's own code: Bliss v2.1.2's water — lib/waterBump.glsl
// (getWaterHeightmap, getWaveNormal, waterCaustics), the water parts of dimensions/all_translucent.fsh (getParallaxDisplacement,
// applyBump, GGX), lib/overworld_fog.glsl (fogPhase) and dimensions/fogBehindTranslucent_pass.fsh (waterVolumetrics_test),
// with Bliss's settings.glsl defaults. Kept as close to the original as the interface allows; what BlockLand changed is
// marked "BlockLand:". Rendering spec §15.
//
// Interface (BlockLand: Iris uniforms → BlockLand's inputs): cameraPosition → BlCamera.xyz (wrapped every 2048 blocks),
// frameTimeCounter → bl_time(), noisetex → Bliss's shaders/texture/noises.png (assets/blockland/textures/bliss/noises.png,
// linear, repeating; texture unit 16 in the lighting pack's programs), the shadow map → bl_shadowMap / bl_cloudShadow.
// Positions are camera-relative world space unless a name says otherwise. Import after <blockland:scene.glsl> and
// <blockland:bl_lighting.glsl>.

#ifndef BLISS_NOISETEX
#define BLISS_NOISETEX
uniform sampler2D noisetex;
#endif

// ---- settings.glsl defaults -------------------------------------------------------------------------------------------
#define Vanilla_like_water
#define WATER_WAVE_STRENGTH 1.0
#define WATER_WAVE_SPEED 1.0
#define Dirt_Amount 0.14
#define Dirt_Scatter_R 0.7
#define Dirt_Scatter_G 0.7
#define Dirt_Scatter_B 0.7
#define Dirt_Absorb_R 0.65
#define Dirt_Absorb_G 0.85
#define Dirt_Absorb_B 1.05
#define Water_Absorb_R 0.2629
#define Water_Absorb_G 0.0565
#define Water_Absorb_B 0.01011
#define rayMarchSampleCount 2
#define MIN_LIGHT_AMOUNT 1.0
#define ambient_brightness 1.0
#define SNELLS_WINDOW
#define WATER_CAUSTICS_BRIGHTNESS 1.0
#define PW_DEPTH 1.5
#define PW_POINTS 2

#define bliss_cameraPosition (BlCamera.xyz)
#define bliss_frameTimeCounter (bl_time())

// ---- lib/waterBump.glsl ------------------------------------------------------------------------------------------------

float waterCaustics(vec3 worldPos, vec3 sunVec) {

	vec3 projectedPos = worldPos - (sunVec/sunVec.y*worldPos.y);
	vec2 pos = projectedPos.xz;

	float heightSum = 0.0;
	float movement = bliss_frameTimeCounter*0.035 * WATER_WAVE_SPEED;
	// movement = 0.0;

	float radiance = 2.39996;
	mat2 rotationMatrix  = mat2(vec2(cos(radiance),  -sin(radiance)),  vec2(sin(radiance),  cos(radiance)));

	vec2 wave_size[3] = vec2[](
		vec2(48.,12.),
		vec2(12.,48.),
		vec2(32.,32.)
	);

	float WavesLarge = max(texture(noisetex, pos / 600.0 ).b,0.1);

	for (int i = 0; i < 3; i++){
		pos = rotationMatrix * pos;
		heightSum += pow(abs(abs(texture(noisetex, pos / wave_size[i] + WavesLarge*0.5 + movement).b * 2.0 - 1.0) * 2.0 - 1.0), 2.0) ;
	}

	float FinalCaustics = exp((1.0 + 5.0 * pow(WavesLarge,0.5)) * (heightSum / 3.0 - 0.5));

	return FinalCaustics;
}

float getWaterHeightmap(vec2 posxz) {

	vec2 pos = posxz;
	float heightSum = 0.0;
	float movement = bliss_frameTimeCounter*0.035 * WATER_WAVE_SPEED;
	// movement = 0.0;

	float radiance = 2.39996;
	mat2 rotationMatrix  = mat2(vec2(cos(radiance),  -sin(radiance)),  vec2(sin(radiance),  cos(radiance)));

	vec2 wave_size[3] = vec2[](
		vec2(48.,12.),
		vec2(12.,48.),
		vec2(32.,32.)
	);

	float WavesLarge = max(texture(noisetex, pos / 600.0 ).b,0.1);

	for (int i = 0; i < 3; i++){
		pos = rotationMatrix * pos;
		heightSum += texture(noisetex, pos / wave_size[i] + WavesLarge*0.5 + movement).b;
	}

	return (heightSum / 60.0) * WavesLarge;
}

vec3 getWaveNormal(vec3 posxz, bool isLOD){

	// vary the normal's "smooth" factor as distance changes, to avoid noise from too much details.
	// float range = pow(clamp(1.0 - length(posxz - cameraPosition)/(32*4),0.0,1.0),2.0);
	// float deltaPos = mix(0.5, 0.1, range);

	float range = min(length(posxz - bliss_cameraPosition) / (16*14.0), 3.0);
	float deltaPos = range + 0.15;

	#ifdef HYPER_DETAILED_WAVES
		deltaPos = 0.025;
	#endif

	vec2 coord = posxz.xz;

	float h0 = getWaterHeightmap(coord);
	float h1 = getWaterHeightmap(coord + vec2(deltaPos,0.0));
	float h3 = getWaterHeightmap(coord + vec2(0.0,deltaPos));


	float xDelta = (h1-h0)/deltaPos;
	float yDelta = (h3-h0)/deltaPos;

	vec3 wave = normalize(vec3(xDelta, yDelta,	1.0-pow(abs(xDelta+yDelta),2.0)));

	return wave;
}

// ---- dimensions/all_translucent.fsh ------------------------------------------------------------------------------------

// BlockLand: viewVector (a varying in Bliss: the vertex position in tangent space) is a parameter.
vec3 getParallaxDisplacement(vec3 posxz, vec3 viewVector) {

	vec3 parallaxPos = posxz;
	vec2 vec = viewVector.xy * (1.0 / float(PW_POINTS)) * 22.0 * PW_DEPTH;
	// float waterHeight = (1.0 - (getWaterHeightmap(posxz.xz)*0.5+0.5)) * 2.0 - 1.0;
	float waterHeight = getWaterHeightmap(posxz.xz) * 2.0;
	parallaxPos.xz -= waterHeight * vec;

	return parallaxPos;
}

vec3 applyBump(mat3 tbnMatrix, vec3 bump, float puddle_values){
	float bumpmult = puddle_values;
	bump = bump * vec3(bumpmult, bumpmult, bumpmult) + vec3(0.0f, 0.0f, 1.0f - bumpmult);
	//
	return normalize(bump*tbnMatrix);
}

float GGX(vec3 n, vec3 v, vec3 l, float r, float f0) {
  r = max(pow(r,2.5), 0.0001);

  vec3 h = l + v;
  float hn = inversesqrt(dot(h, h));

  float dotLH = clamp(dot(h,l)*hn,0.,1.);
  float dotNH = clamp(dot(h,n)*hn,0.,1.) ;
  float dotNL = clamp(dot(n,l),0.,1.);
  float dotNHsq = dotNH*dotNH;

  float denom = dotNHsq * r - dotNHsq + 1.;
  float D = r / (3.141592653589793 * denom * denom);

  float F = f0 + (1. - f0) * exp2((-5.55473*dotLH-6.98316)*dotLH);
  float k2 = .25 * r;

  return dotNL * D * F / (dotLH*dotLH*(1.0-k2)+k2);
}

float luma(vec3 color) {
	return dot(color,vec3(0.21, 0.72, 0.07));
}

// The NORMALS block of all_translucent.fsh for water, as one function. BlockLand: `normal` is the face's normal facing
// the camera (Bliss: the vertex normal), `pos` the camera-relative position; vanilla's vertices carry no tangents, so the
// tangent of a level face runs along world x (its binormal along z) and a side face's along it horizontally. Our rain
// drops and ripples (spec §15) are added on top of the wave normal where the face is level, faded by `footprint`
// (blocks per pixel). Returns the bumped world normal.
vec3 bliss_waterNormal(vec3 pos, vec3 normal, float footprint) {
	vec3 worldSpaceNormal = normal;
	vec3 tangent = abs(normal.y) > 0.5 ? normalize(vec3(1.0, 0.0, 0.0) - normal * normal.x)
			: normalize(vec3(-normal.z, 0.0, normal.x) + vec3(1e-5, 0.0, 0.0));
	vec3 tangent2 = normalize(cross(tangent.rgb,normal));
	mat3 tbnMatrix = mat3(tangent.x, tangent2.x, normal.x,
						  tangent.y, tangent2.y, normal.y,
						  tangent.z, tangent2.z, normal.z);
	vec3 viewVector = normalize(tbnMatrix * pos);

	vec3 NormalTex = vec3(0.0, 0.0, 1.0);
	vec3 posxz = pos + bliss_cameraPosition;

	// make the waves flow in the direction the water faces, except for perfectly up facing parts.
	if(abs(worldSpaceNormal.y) < 0.9995) posxz.xz -= (posxz.y + bliss_frameTimeCounter*3 * WATER_WAVE_SPEED) * normalize(worldSpaceNormal.xz) ;

	posxz.xyz = getParallaxDisplacement(posxz, viewVector);
	vec3 bump = normalize(getWaveNormal(posxz, false));

	float bumpmult = 10.0 * WATER_WAVE_STRENGTH;
	bump = bump * vec3(bumpmult, bumpmult, bumpmult) + vec3(0.0f, 0.0f, 1.0f - bumpmult);

	NormalTex.xyz = bump;

	vec3 bumped = applyBump(tbnMatrix, NormalTex.xyz, 1.0);

	// BlockLand: rain drops and the rings of whatever disturbed the water, on level faces
	float level = smoothstep(0.9, 0.99, abs(normal.y));
	if (level > 0.0) {
		vec2 world = pos.xz + BlCamera.xz;
		vec2 s = vec2(0.0);
		float fine = 1.0 - smoothstep(0.04, 0.16, footprint);
		if (fine > 0.0) s += bl_rainSlope(world, bl_time()) * fine;
		float rings = 1.0 - smoothstep(0.08, 0.35, footprint);
		if (rings > 0.0) s += bl_rippleSlope(pos) * rings;
		bumped = normalize(bumped + vec3(-s.x, 0.0, -s.y) * sign(normal.y) * level);
	}
	return bumped;
}

// ---- lib/overworld_fog.glsl ----------------------------------------------------------------------------------------------

float fogPhase(float lightPoint){
	float linear = 1.0 - clamp(lightPoint*0.5+0.5,0.0,1.0);
	float linear2 = 1.0 - clamp(lightPoint,0.0,1.0);

	float exponential = exp2(pow(linear,0.3) * -15.0 ) * 1.5;
	exponential += sqrt(exp2(sqrt(linear) * -12.5));

	return exponential;
}

// ---- dimensions/fogBehindTranslucent_pass.fsh ----------------------------------------------------------------------------

// BlockLand: rayStart / rayEnd are camera-relative world positions (Bliss: view positions projected into shadow space);
// the shadow is bl_shadowMap × bl_cloudShadow (Bliss: shadow2D × GetCloudShadow_VLFOG, VL_CLOUDS_SHADOWS on by default).
vec4 waterVolumetrics_test( vec3 rayStart, vec3 rayEnd, float estEndDepth, float estSunDepth, float rayLength, float dither, vec3 waterCoefs, vec3 scatterCoef, vec3 ambient, vec3 lightSource, float VdotL){
	int spCount = rayMarchSampleCount;

	vec3 start = rayStart;
	vec3 end = rayEnd;
	vec3 dV = (end-start);

	//limit ray length at 32 blocks for performance and reducing integration error
	//you can't see above this anyway
	float maxZ = min(rayLength,12.0)/(1e-8+rayLength);
	dV *= maxZ;
	rayLength *= maxZ;
	estEndDepth *= maxZ;
	estSunDepth *= maxZ;

	vec3 wpos = rayStart;

	vec3 newabsorbance = exp(-rayLength * waterCoefs);	// No need to take the integrated value
	float phase = fogPhase(VdotL) * 5.0;
	vec3 absorbance = vec3(1.0);
	vec3 vL = vec3(0.0);

	ambient = max(ambient * (normalize(wpos).y*0.3+0.7),0.0);

	float expFactor = 11.0;
	for (int i=0;i<spCount;i++) {
		float d = (pow(expFactor, float(i+dither)/float(spCount))/expFactor - 1.0/expFactor)/(1-1.0/expFactor);
		float dd = pow(expFactor, float(i+dither)/float(spCount)) * log(expFactor) / float(spCount)/(expFactor-1.0);

		vec3 spPos = start.xyz + dV*d;
		vec3 sh = vec3(bl_shadowMap(spPos, vec3(0.0), gl_FragCoord.xy));
		sh *= bl_cloudShadow(spPos);

		vec3 sunMul = exp(-estSunDepth * d * waterCoefs * 1.1);
		vec3 ambientMul = exp(-estEndDepth * d * waterCoefs );

		vec3 Directlight = ((lightSource * sh) * phase * sunMul) ;
		vec3 Indirectlight = max(ambient * ambientMul, vec3(0.01,0.2,0.4) * ambientMul * MIN_LIGHT_AMOUNT * 0.03) ;

		vec3 light = (Indirectlight + Directlight) * scatterCoef;

		vL += (light - light * exp(-waterCoefs * dd * rayLength)) / waterCoefs * absorbance;
		absorbance *= exp(-waterCoefs * dd * rayLength);
	}
	// inColor += vL;
    return vec4( vL, dot(newabsorbance,vec3(0.335)));
}

// BlockLand: the sign Bliss keeps in lightCol.a — the light source is the sun (1) or the moon (-1).
float bliss_lightSign() {
	return BlSunDir.w > 0.5 ? 1.0 : -1.0;
}
