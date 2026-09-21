#pragma once

#if defined(__ANDROID__) || defined(ANDROID)
#include <jni.h>
#include <android/bitmap.h>
#else
typedef void* JNIEnv;
typedef void* jobjectArray;
#endif
#include <vector>
#include <string>
#include <cstdint>
#include <memory>
#include <map>

namespace videogen {

struct TaeDecodeConfig {
    int latentChannels = 16;
    int latentFrames = 5;
    int latentHeight = 32;
    int latentWidth = 48;
    int targetFrames = 17;
    int targetHeight = 256;
    int targetWidth = 384;
    std::string safetensorsPath;
};

struct TensorRef {
    size_t offset = 0;
    size_t size = 0;
    std::vector<int64_t> shape;
    std::string dtype;
};

/**
 * High-performance Neural 3D-VAE Video Decoder for Wan 2.1.
 * Evaluates causal 3D convolutional ResNet layers and spatio-temporal upsamplers
 * directly from 'wan_2.1_vae.safetensors' (BF16 zero-copy mmap) into ARGB_8888 Bitmaps.
 */
class TaeVideoDecoder {
public:
    TaeVideoDecoder();
    ~TaeVideoDecoder();

    bool loadWeights(const std::string& safetensorsPath);
    void unloadWeights();

    /**
     * Decodes 16-channel latents [16, latentFrames, latentHeight, latentWidth] into AndroidBitmaps.
     */
    bool decodeLatentsToBitmaps(
        JNIEnv* env,
        const float* latents,
        jobjectArray bitmapArray,
        const TaeDecodeConfig& config
    );

private:
    void* mappedData_ = nullptr;
    size_t mappedSize_ = 0;
    int fd_ = -1;
    bool hasSafetensors_ = false;

    // Tensor index map parsed from safetensors JSON header
    std::map<std::string, TensorRef> tensors_;
    const uint8_t* tensorsDataStart_ = nullptr;

    bool parseSafetensorsHeader();

    // Neural decode routines
    float readBf16(size_t tensorDataOffset, size_t elementIndex) const;

    void decodeSingleFrameNeural(
        const float* allLatents,
        int F_lat,
        int H_lat,
        int W_lat,
        int frameIdx,
        int totalTargetFrames,
        uint32_t* outPixels,
        int targetH,
        int targetW
    );
};

} // namespace videogen
