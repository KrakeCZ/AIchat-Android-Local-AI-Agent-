#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/hardware_buffer_jni.h>
#include <cmath>
#include <algorithm>

#define LOG_TAG "AcademicYuvJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_agent_vision_nightmode_AcademicYuvEngine_nativeYuvPlaneToFloatBuffer(
        JNIEnv *env, jobject /* this */,
        jobject yPlaneBufferObj, jint width, jint height, jint rowStride,
        jint inputWidth, jint inputHeight,
        jobject floatBufferObj, jfloat scale, jfloat offset, jboolean isFloatModel) {

    if (!yPlaneBufferObj || !floatBufferObj) {
        LOGE("Buffers cannot be null");
        return;
    }

    void *yDataPtr = env->GetDirectBufferAddress(yPlaneBufferObj);
    if (!yDataPtr) {
        LOGE("Failed to get direct buffer pointer for Y plane");
        return;
    }

    uint8_t *yBuffer = static_cast<uint8_t *>(yDataPtr);
    void *outBuffer = env->GetDirectBufferAddress(floatBufferObj);

    if (!outBuffer) {
        LOGE("Failed to get direct buffer pointers");
        return;
    }

    int actualRowStride = rowStride > 0 ? rowStride : width;

    if (isFloatModel) {
        float *outFloat = static_cast<float *>(outBuffer);
        int outIndex = 0;
        for (int y = 0; y < inputHeight; ++y) {
            int srcY = (y * height) / inputHeight;
            for (int x = 0; x < inputWidth; ++x) {
                int srcX = (x * width) / inputWidth;
                int yIndex = srcY * actualRowStride + srcX;
                
                uint8_t yValue = yBuffer[yIndex];
                float normValue = (yValue / scale) - offset;
                
                outFloat[outIndex++] = normValue; // R
                outFloat[outIndex++] = normValue; // G
                outFloat[outIndex++] = normValue; // B
            }
        }
    } else {
        uint8_t *outByte = static_cast<uint8_t *>(outBuffer);
        int outIndex = 0;
        for (int y = 0; y < inputHeight; ++y) {
            int srcY = (y * height) / inputHeight;
            for (int x = 0; x < inputWidth; ++x) {
                int srcX = (x * width) / inputWidth;
                int yIndex = srcY * actualRowStride + srcX;
                
                uint8_t yValue = yBuffer[yIndex];
                
                outByte[outIndex++] = yValue;
                outByte[outIndex++] = yValue;
                outByte[outIndex++] = yValue;
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_agent_vision_nightmode_AcademicYuvEngine_nativeFloatBufferToArgb(
        JNIEnv *env, jobject /* this */,
        jobject modelOutBufferObj, jint inputWidth, jint inputHeight,
        jobject bitmapOutObj, jint rotation, jfloat scale, jfloat offset, jboolean isFloatModel) {

    if (!modelOutBufferObj || !bitmapOutObj) {
        LOGE("Buffers cannot be null");
        return;
    }

    void *outBuffer = env->GetDirectBufferAddress(modelOutBufferObj);
    if (!outBuffer) {
        LOGE("Failed to get direct buffer");
        return;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmapOutObj, &info) < 0) {
        LOGE("Failed to get Bitmap info");
        return;
    }

    void *pixelsPtr = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmapOutObj, &pixelsPtr) < 0 || !pixelsPtr) {
        LOGE("Failed to lock Bitmap pixels");
        return;
    }

    // RGBA_8888 -> 4 bytes per pixel. Stride is in bytes, so we divide by 4 for uint32_t stride.
    uint32_t *pixels = static_cast<uint32_t *>(pixelsPtr);
    int hwStride = info.stride / 4; 
    
    int p = 0;
    if (isFloatModel) {
        float *outFloat = static_cast<float *>(outBuffer);
        for (int y = 0; y < inputHeight; ++y) {
            for (int x = 0; x < inputWidth; ++x) {
                float r_f = (outFloat[p++] + offset) * scale;
                float g_f = (outFloat[p++] + offset) * scale;
                float b_f = (outFloat[p++] + offset) * scale;
                
                uint32_t r = std::clamp(static_cast<int>(r_f), 0, 255);
                uint32_t g = std::clamp(static_cast<int>(g_f), 0, 255);
                uint32_t b = std::clamp(static_cast<int>(b_f), 0, 255);
                
                int destX = x;
                int destY = y;
                
                if (rotation == 90) {
                    destX = inputHeight - 1 - y;
                    destY = x;
                } else if (rotation == 180) {
                    destX = inputWidth - 1 - x;
                    destY = inputHeight - 1 - y;
                } else if (rotation == 270) {
                    destX = y;
                    destY = inputWidth - 1 - x;
                }
                
                int rowOffset = destY * hwStride;
                
                uint8_t* px = reinterpret_cast<uint8_t*>(&pixels[rowOffset + destX]);
                px[0] = r; // R
                px[1] = g; // G
                px[2] = b; // B
                px[3] = 255; // A
            }
        }
    } else {
        uint8_t *outByte = static_cast<uint8_t *>(outBuffer);
        for (int y = 0; y < inputHeight; ++y) {
            for (int x = 0; x < inputWidth; ++x) {
                uint8_t r = outByte[p++];
                uint8_t g = outByte[p++];
                uint8_t b = outByte[p++];
                
                int destX = x;
                int destY = y;
                
                if (rotation == 90) {
                    destX = inputHeight - 1 - y;
                    destY = x;
                } else if (rotation == 180) {
                    destX = inputWidth - 1 - x;
                    destY = inputHeight - 1 - y;
                } else if (rotation == 270) {
                    destX = y;
                    destY = inputWidth - 1 - x;
                }
                
                int rowOffset = destY * hwStride;
                
                uint8_t* px = reinterpret_cast<uint8_t*>(&pixels[rowOffset + destX]);
                px[0] = r; // R
                px[1] = g; // G
                px[2] = b; // B
                px[3] = 255; // A
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmapOutObj);
}
