#version 450

layout(binding = 0) uniform sampler2D texSampler;

layout(push_constant) uniform PC {
    float ndcX0;   // tepi kiri quad di layar (NDC, sebelum pra-rotasi)
    float ndcY0;
    float ndcX1;   // tepi kanan quad di layar
    float ndcY1;
    int   useTexAlpha; // 0 = opaque, 1 = alpha premultiplied, 2 = alpha straight (kursor)
    float strength;
    float profile;
    float srcX0;   // rentang X window pada scene (NDC, sebelum warp)
    float srcX1;
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265358979323846;

float hybrid_curve(float x, float prof) {
    float sin_shape = sin(0.5 * PI * x);
    float x2 = x * x;
    float x5 = x2 * x2 * x;
    float quintic = 0.5 * x5 - 1.5 * x2 * x + 2.0 * x;
    return mix(sin_shape, quintic, prof);
}

void main() {
    // Posisi X piksel ini di layar -> posisi X pada scene lewat kurva Soft Stretch. Kurva berlaku untuk seluruh
    // layar (bukan per window), jadi window yang tidak memenuhi layar tetap berada di tempat yang benar.
    float x = mix(pc.ndcX0, pc.ndcX1, fragTexCoord.x);
    float curved = hybrid_curve(x, pc.profile);
    float u = mix(x, curved, pc.strength);

    float span = pc.srcX1 - pc.srcX0;
    if (span <= 0.0) discard;
    float tx = (u - pc.srcX0) / span;
    if (tx < 0.0 || tx > 1.0) discard;   // di luar window ini

    vec4 c = texture(texSampler, vec2(tx, fragTexCoord.y));
    if (pc.useTexAlpha == 0)      outColor = vec4(c.rgb, 1.0);
    else if (pc.useTexAlpha == 1) outColor = c;
    else                          outColor = vec4(c.rgb * c.a, c.a);
}
