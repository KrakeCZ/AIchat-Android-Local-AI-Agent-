#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

// EGL & OpenGL
#define EGL_EGLEXT_PROTOTYPES 1
#define GL_GLEXT_PROTOTYPES   1
#include <android/hardware_buffer_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <GLES3/gl3ext.h>
#include <GLES2/gl2ext.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/jni.h>  // <-- OPRAVA: Nezbytné pro JNI a MediaCodec (HW akceleraci)
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>  // Garantuje 100% kompatibilitu vzorků pro AC3/AAC/MP3
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/hwcontext.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG   "NativeMediaEngine_PRO_v5"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static std::atomic<bool> g_isCancelled{ false };

static std::string ffmpegError(int errCode) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = { 0 };
    av_strerror(errCode, buf, sizeof(buf));
    return std::string(buf);
}

// --- RAII wrappers ---
struct AVPacketDeleter {
    void operator()(AVPacket * p) const {
        if (p) {
            av_packet_free(&p);
        }
    }
};

struct AVFrameDeleter {
    void operator()(AVFrame * f) const {
        if (f) {
            av_frame_free(&f);
        }
    }
};

struct AVBufferRefDeleter {
    void operator()(AVBufferRef * b) const {
        if (b) {
            av_buffer_unref(&b);
        }
    }
};

using ScopedAVPacket    = std::unique_ptr<AVPacket, AVPacketDeleter>;
using ScopedAVFrame     = std::unique_ptr<AVFrame, AVFrameDeleter>;
using ScopedAVBufferRef = std::unique_ptr<AVBufferRef, AVBufferRefDeleter>;

// --- Custom AVIO (SAF / scoped storage) ---
struct FdAvioOpaque {
    int  fd;
    bool seekable;
};

static int fdAvioRead(void * opaque, uint8_t * buf, int size) {
    auto * o = static_cast<FdAvioOpaque *>(opaque);
    int    n = static_cast<int>(::read(o->fd, buf, static_cast<size_t>(size)));
    if (n == 0) {
        return AVERROR_EOF;
    }
    if (n < 0) {
        return AVERROR(errno);
    }
    return n;
}

static int64_t fdAvioSeek(void * opaque, int64_t offset, int whence) {
    auto * o = static_cast<FdAvioOpaque *>(opaque);
    if (!o->seekable) {
        return AVERROR(ENOSYS);
    }
    if (whence == AVSEEK_SIZE) {
        struct stat64 st{};
        if (::fstat64(o->fd, &st) == 0) {
            return static_cast<int64_t>(st.st_size);
        }
        return -1;
    }
    int64_t pos = ::lseek64(o->fd, offset, whence);
    return (pos < 0) ? AVERROR(errno) : pos;
}

static void fdAvioFree(FdAvioOpaque ** p) {
    if (p && *p) {
        ::close((*p)->fd);
        delete *p;
        *p = nullptr;
    }
}

// 1MB AVIO buffer — redukuje I/O syscalls ~16x oproti 64KB
static AVFormatContext * openFormatFromFd(int originalFd, FdAvioOpaque ** outOpaque) {
    int dupFd = ::dup(originalFd);
    if (dupFd < 0) {
        LOGE("dup(%d) selhal: %s", originalFd, strerror(errno));
        return nullptr;
    }
    ::lseek64(dupFd, 0, SEEK_SET);
    // 🚀 OPRAVA AUDIO: fstat64 + S_ISREG na Android SAF (content://) často vrací false.
    // Jediný spolehlivý způsob, jak zjistit seekovatelnost, je zkusit seeknout.
    bool seekable          = (::lseek64(dupFd, 0, SEEK_CUR) >= 0);
    *outOpaque             = new FdAvioOpaque{ dupFd, seekable };
    constexpr int BUF_SIZE = 1024 * 1024;
    auto *        iobuf    = static_cast<unsigned char *>(av_malloc(BUF_SIZE));
    if (!iobuf) {
        fdAvioFree(outOpaque);
        return nullptr;
    }
    AVIOContext * avio =
        avio_alloc_context(iobuf, BUF_SIZE, 0, *outOpaque, fdAvioRead, nullptr, seekable ? fdAvioSeek : nullptr);
    if (!avio) {
        av_free(iobuf);
        fdAvioFree(outOpaque);
        return nullptr;
    }
    AVFormatContext * ctx = avformat_alloc_context();
    if (!ctx) {
        av_free(avio->buffer);
        av_free(avio);
        fdAvioFree(outOpaque);
        return nullptr;
    }
    ctx->pb = avio;

    // Zásadní: musíme nechat FFmpeg přečíst hlavičku kontejneru přes náš custom AVIO,
    // jinak inFmtCtx->streams bude prázdný a audio stopa se nenajde!
    AVDictionary *opts = nullptr;
    av_dict_set(&opts, "probesize", "100000000", 0);
    av_dict_set(&opts, "analyzeduration", "100000000", 0);

    if (avformat_open_input(&ctx, nullptr, nullptr, &opts) < 0) {
        LOGE("openFormatFromFd: avformat_open_input selhal na custom AVIO!");
        av_dict_free(&opts);
        av_free(avio->buffer);
        avio_context_free(&avio);
        fdAvioFree(outOpaque);
        avformat_free_context(ctx);
        return nullptr;
    }
    av_dict_free(&opts);

    return ctx;
}

static int parseProcFdPath(const std::string & path) {
    const std::string prefix = "/proc/self/fd/";
    if (path.length() > prefix.length() && path.compare(0, prefix.length(), prefix) == 0) {
        try {
            return std::stoi(path.substr(prefix.length()));
        } catch (...) {
            return -1;
        }
    }
    return -1;
}

// --- HW device init + encoder probe ---
static AVBufferRef * createHardwareDeviceContext() {
    AVBufferRef * hwCtx = nullptr;
    int           err   = av_hwdevice_ctx_create(&hwCtx, AV_HWDEVICE_TYPE_MEDIACODEC, "mediacodec", nullptr, 0);
    if (err < 0) {
        LOGW("HW device init selhal: %s — fallback SW.", ffmpegError(err).c_str());
        return nullptr;
    }
    LOGI("HW Device Context (MediaCodec) inicializovan.");
    return hwCtx;
}

struct EncoderSelection {
    const AVCodec * codec;
    bool            isHardware;
    const char *    name;
};

static EncoderSelection probeVideoEncoder(bool useHardware, int codecIndex) {
    const AVCodec * c          = nullptr;
    std::string     targetName = "";

    if (useHardware) {
        switch (codecIndex) {
            case 0:
                targetName = "h264_mediacodec";
                break;
            case 1:
                targetName = "hevc_mediacodec";
                break;
            case 2:
                targetName = "vp9_mediacodec";
                break;
            case 3:
                targetName = "av1_mediacodec";
                break;
            case 4:
                targetName = "mpeg4_mediacodec";
                break;
            case 5:
                targetName = "vp8_mediacodec";
                break;  // 🚀 YML Inteligence: Přidán VP8 HW
            default:
                targetName = "h264_mediacodec";
                break;
        }
        c = avcodec_find_encoder_by_name(targetName.c_str());
        if (c) {
            LOGI("Encoder HW: %s", c->name);
            return { c, true, c->name };
        }
        LOGW("HW encoder %s nenalezen. Fallback na SW.", targetName.c_str());
    }

    // SW fallback
    switch (codecIndex) {
        case 0:
            targetName = "mpeg4"; // H264 SW Fallback (LGPL)
            break;
        case 1:
            targetName = "mpeg4"; // H265 SW Fallback (LGPL)
            break;
        case 2:
            targetName = "libvpx-vp9";
            break;
        case 3:
            targetName = "libaom-av1";
            break;
        case 4:
            targetName = "mpeg4";
            break;
        case 5:
            targetName = "libvpx";
            break;  // 🚀 YML Inteligence: VP8 SW pro WebM
        default:
            targetName = "mpeg4"; // LGPL Fallback
            break;
    }
    c = avcodec_find_encoder_by_name(targetName.c_str());
    if (c) {
        LOGI("Encoder SW: %s", c->name);
        return { c, false, c->name };
    }

    LOGE("Zadny encoder pro %s nenalezen!", targetName.c_str());
    return { nullptr, false, "none" };
}

// ==========================================================================
// JNI: nativeCancel
// ==========================================================================
extern "C" JNIEXPORT void JNICALL Java_com_example_llama_media_NativeVideoEngine_nativeCancel(JNIEnv *, jobject) {
    g_isCancelled.store(true, std::memory_order_release);
    LOGI("nativeCancel: Zruseni pozadano.");
}

namespace videogen {
    void setJavaVM(JavaVM* vm);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void * reserved) {
    LOGI("JNI_OnLoad: Inicializace FFmpeg Java VM pro MediaCodec HW Akceleraci.");
    av_jni_set_java_vm(vm, nullptr);
    videogen::setJavaVM(vm);
    return JNI_VERSION_1_6;
}

// ==========================================================================
// EGL COMPOSITOR (ZERO-COPY MIXÁŽ VIDEA A TITULKŮ)
// ==========================================================================
static const char * VERTEX_SHADER = R"(
    #version 300 es
    in vec4 aPosition;
    in vec4 aTexCoord;
    out vec2 vVideoTexCoord;
    out vec2 vSubTexCoord;
    uniform mat4 uTransformM;
    void main() {
        gl_Position = aPosition;
        vVideoTexCoord = (uTransformM * aTexCoord).xy;
        vSubTexCoord = aTexCoord.xy;
    }
)";

static const char * FRAGMENT_SHADER = R"(
    #version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision mediump float;
    in vec2 vVideoTexCoord;
    in vec2 vSubTexCoord;
    out vec4 outColor;

    uniform samplerExternalOES uVideo; // YUV video stream z kodéru
    uniform sampler2D uSubtitle;       // Standardní RGBA 2D textura pro Bitmapu

    void main() {
        vec4 videoColor = texture(uVideo, vVideoTexCoord);
        // Android Bitmapy mají Y osu z pohledu OpenGL naruby, musíme ji převrátit
        vec2 subTexCoord = vec2(vSubTexCoord.x, 1.0 - vSubTexCoord.y);
        vec4 subColor = texture(uSubtitle, subTexCoord);

        // Alpha blending (Titulky nakreslené přes video)
        outColor = videoColor * (1.0 - subColor.a) + subColor;
    }
)";

class GpuCompositor {
  public:
    GLuint program = 0;
    GLint  posLoc, texLoc, uVideoLoc, uSubtitleLoc, uTransformMLoc;

    GLuint subtitleTexId = 0;
    bool   isInitialized = false;

    void release() {
        if (subtitleTexId != 0) {
            glDeleteTextures(1, &subtitleTexId);
            subtitleTexId = 0;
        }
        if (program != 0) {
            glDeleteProgram(program);
            program = 0;
        }
        isInitialized = false;
    }

    GLuint compileShader(GLenum type, const char * source) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            char infoLog[512];
            glGetShaderInfoLog(shader, 512, nullptr, infoLog);
            LOGE("Shader Error: %s", infoLog);
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    bool init() {
        if (isInitialized) {
            return true;
        }

        GLuint vShader = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
        GLuint fShader = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program        = glCreateProgram();
        glAttachShader(program, vShader);
        glAttachShader(program, fShader);
        glLinkProgram(program);
        glDeleteShader(vShader);
        glDeleteShader(fShader);

        uVideoLoc      = glGetUniformLocation(program, "uVideo");
        uSubtitleLoc   = glGetUniformLocation(program, "uSubtitle");
        uTransformMLoc = glGetUniformLocation(program, "uTransformM");
        posLoc         = glGetAttribLocation(program, "aPosition");
        texLoc         = glGetAttribLocation(program, "aTexCoord");

        isInitialized = (program != 0);
        return isInitialized;
    }

    void compositeFrame(JNIEnv * env, jobject jBitmap, GLuint videoTexId, const GLfloat * transformMatrix) {
        if (!isInitialized) {
            return;
        }

        // Vytvoření a nastavení 2D textury pro titulky
        if (subtitleTexId == 0) {
            glGenTextures(1, &subtitleTexId);
            glBindTexture(GL_TEXTURE_2D, subtitleTexId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            // Nutné pro kompatibilitu s NPOT (Non-Power-Of-Two) rozlišením na Adrenu!
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        } else {
            glBindTexture(GL_TEXTURE_2D, subtitleTexId);
        }

        // Získání pixelů z Kotlin Bitmapy a jejich nahrání do GPU (Zero-Driver-Bug metoda)
        if (jBitmap != nullptr) {
            AndroidBitmapInfo info;
            void *            pixels = nullptr;
            if (AndroidBitmap_getInfo(env, jBitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS) {
                // Nastavíme viewport přesně na velikost výstupního videa
                glViewport(0, 0, info.width, info.height);

                if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
                    // Nahrání pixelů ze systémové RAM přímo do VRAM
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, info.width, info.height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                                 pixels);
                    AndroidBitmap_unlockPixels(env, jBitmap);
                }
            }
        }

        glUseProgram(program);

        // Mapování videa (Texturovací jednotka 0)
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTexId);
        glUniform1i(uVideoLoc, 0);

        // Mapování titulků (Texturovací jednotka 1)
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, subtitleTexId);
        glUniform1i(uSubtitleLoc, 1);

        static const GLfloat vertices[]  = { -1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f };
        static const GLfloat texCoords[] = { 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f };

        glEnableVertexAttribArray(posLoc);
        glVertexAttribPointer(posLoc, 2, GL_FLOAT, GL_FALSE, 0, vertices);

        glEnableVertexAttribArray(texLoc);
        glVertexAttribPointer(texLoc, 2, GL_FLOAT, GL_FALSE, 0, texCoords);

        glUniformMatrix4fv(uTransformMLoc, 1, GL_FALSE, transformMatrix);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(posLoc);
        glDisableVertexAttribArray(texLoc);
    }
};

static GpuCompositor compositor;

extern "C" JNIEXPORT void JNICALL Java_com_example_llama_media_NativeVideoEngine_nativeReleaseCompositor(JNIEnv * env,
                                                                                                         jclass) {
    compositor.release();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_media_NativeVideoEngine_nativeCompositeFrame(JNIEnv * env,
                                                                    jobject,
                                                                    jobject     jBitmap,
                                                                    jint        videoTexId,
                                                                    jfloatArray jTransformMatrix) {
    if (!compositor.isInitialized) {
        compositor.init();
    }
    jfloat * matrixElements = env->GetFloatArrayElements(jTransformMatrix, nullptr);
    compositor.compositeFrame(env, jBitmap, (GLuint) videoTexId, matrixElements);
    env->ReleaseFloatArrayElements(jTransformMatrix, matrixElements, JNI_ABORT);
}

// ==========================================================================
// NATIVE COMPOSITOR & MUXER (ZERO-COPY PIPELINE S PARALELNÍM AUDIEM)
// ==========================================================================

// Bezpečnostní obálka pro thread-safe zápis a odložený zápis hlavičky (Race-Condition Fix)
struct MuxerContext {
    AVFormatContext * fmtCtx = nullptr;
    std::mutex        writeMutex;
    AVStream *        videoStream = nullptr;
    AVStream *        audioStream = nullptr;

    bool                    headerWritten   = false;
    bool                    videoConfigured = false;
    bool                    audioConfigured = false;
    std::vector<AVPacket *> pendingPackets;  // Fronta pro rámce, které přijdou před hlavičkou

    // 🚀 OPRAVA AUDIO THROTTLINGU (1.5s Bug)
    std::atomic<int64_t> lastVideoPtsUs{ 0 };
    std::atomic<bool>    videoEof{ false };

    int64_t last_muxed_video_dts = -1;  // 🚀 TOTO CHYBĚLO: Hlídač rostoucího času

    // 🚀 NOVÉ: Bezpečnostní destruktor pro smazání neuložených paketů při chybě
    ~MuxerContext() {
        for (auto * pkt : pendingPackets) {
            av_packet_free(&pkt);
        }
        pendingPackets.clear();
    }

    void checkAndWriteHeader() {
        if (headerWritten) {
            return;
        }
        if (videoConfigured && audioConfigured) {
            LOGI("Muxer: Oba streamy nakonfigurovany. Zapisuji hlavicku kontejneru...");

            if (!(fmtCtx->oformat->flags & AVFMT_NOFILE)) {
                // 1. Získáme URL (moderní náhrada za filename)
                const char * url = fmtCtx->url;

                // 2. Bezpečnostní pojistka proti pádu
                if (!url) {
                    LOGE("Muxer: URL pro vystup je null!");
                    return;
                }

                // 3. Otevření I/O kontextu pro zápis
                int ioRet = avio_open(&fmtCtx->pb, url, AVIO_FLAG_WRITE);
                if (ioRet < 0) {
                    LOGE("Muxer: avio_open selhal pro %s", url);
                    return;
                }
            }

            int ret = avformat_write_header(fmtCtx, nullptr);
            if (ret < 0) {
                LOGE("Muxer: avformat_write_header selhal: %s", ffmpegError(ret).c_str());
            } else {
                LOGI("Muxer: Hlavicka zapsana. Vyprazdnuji frontu (%zu cesty paketu).", pendingPackets.size());
                headerWritten = true;
                for (auto * pkt : pendingPackets) {
                    int wret = av_interleaved_write_frame(fmtCtx, pkt);
                    if (wret < 0) {
                        char err_buf[AV_ERROR_MAX_STRING_SIZE];
                        av_strerror(wret, err_buf, AV_ERROR_MAX_STRING_SIZE);
                        LOGE("Muxer: CHYBA ZAPISU PAKETU (pending): %d (%s)", wret, err_buf);
                    }
                    av_packet_free(&pkt);
                }
                pendingPackets.clear();
            }
        }
    }
};

extern "C" JNIEXPORT jlong JNICALL Java_com_example_llama_media_NativeVideoEngine_nativeInitMuxer(JNIEnv * env,
                                                                                                  jobject,
                                                                                                  jstring outPathStr,
                                                                                                  jint    width,
                                                                                                  jint    height,
                                                                                                  jint    fps,
                                                                                                  jint    codecIndex) {
    const char *   outPath = env->GetStringUTFChars(outPathStr, nullptr);
    MuxerContext * muxCtx  = new MuxerContext();

    avformat_alloc_output_context2(&muxCtx->fmtCtx, nullptr, nullptr, outPath);
    if (!muxCtx->fmtCtx) {
        LOGE("Nelze vytvorit output context pro %s", outPath);
        delete muxCtx;
        env->ReleaseStringUTFChars(outPathStr, outPath);
        return 0;
    }

    // --- VIDEO STREAM ---
    muxCtx->videoStream                       = avformat_new_stream(muxCtx->fmtCtx, nullptr);
    muxCtx->videoStream->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;

    switch (codecIndex) {
        case 1:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_HEVC;
            break;
        case 2:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_VP9;
            break;
        case 3:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_AV1;
            break;
        case 4:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_MPEG4;
            break;
        case 5:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_VP8;
            break;
        case 6:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_MPEG2VIDEO;
            break;
        case 7:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_VC1;
            break;
        case 8:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_WMV1;
            break;
        case 9:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_WMV2;
            break;
        case 10:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_WMV3;
            break;
        case 11:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_THEORA;
            break;
        case 12:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_PRORES;
            break;
        case 13:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_DNXHD;
            break;
        case 14:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_HUFFYUV;
            break;
        case 15:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_FFV1;
            break;
        case 16:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_MJPEG;
            break;
        case 17:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_PNG;
            break;
        case 18:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_GIF;
            break;
        case 19:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_WEBP;
            break;
        case 20:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_RAWVIDEO;
            break;
        case 0:
        default:
            muxCtx->videoStream->codecpar->codec_id = AV_CODEC_ID_H264;
            break;
    }

    muxCtx->videoStream->codecpar->width  = width;
    muxCtx->videoStream->codecpar->height = height;
    muxCtx->videoStream->time_base        = { 1, 1000000 };
    muxCtx->videoStream->avg_frame_rate   = { fps, 1 };
    muxCtx->videoStream->r_frame_rate     = { fps, 1 };

    // --- AUDIO STREAM ---
    muxCtx->audioStream                       = avformat_new_stream(muxCtx->fmtCtx, nullptr);
    muxCtx->audioStream->codecpar->codec_type = AVMEDIA_TYPE_AUDIO;

    // 🚀 DYNAMICKÝ VÝBĚR AUDIO KODEKU PODLE KONTEJNERU (např. WebM -> Opus/Vorbis, MP4 -> AAC)
    muxCtx->audioStream->codecpar->codec_id = muxCtx->fmtCtx->oformat->audio_codec;
    if (muxCtx->audioStream->codecpar->codec_id == AV_CODEC_ID_NONE) {
        muxCtx->audioStream->codecpar->codec_id = AV_CODEC_ID_AAC;  // Bezpečný fallback
    }
    
    // Pokud je kontejner AVI, AAC dělá problémy. Vynutíme MP3.
    std::string pathStr = outPath;
    if (pathStr.length() >= 4 && pathStr.substr(pathStr.length() - 4) == ".avi") {
        muxCtx->audioStream->codecpar->codec_id = AV_CODEC_ID_MP3;
    }

    // Později v AudioWorkeru encoder parametry plně přepíší tyto dočasné hodnoty (kromě codec_id).
    muxCtx->audioStream->codecpar->sample_rate = 44100;
    muxCtx->audioStream->time_base             = { 1, 44100 };

    env->ReleaseStringUTFChars(outPathStr, outPath);
    LOGI("Muxer pre-inicializovan (čeka na extradata z MediaCodecu a Audia).");
    return reinterpret_cast<jlong>(muxCtx);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_llama_media_NativeVideoEngine_nativeWritePacket(JNIEnv * env,
                                                                                                   jobject,
                                                                                                   jlong   muxerPtr,
                                                                                                   jobject directBuffer,
                                                                                                   jint    offset,
                                                                                                   jint    size,
                                                                                                   jlong   ptsUs,
                                                                                                   jboolean isKeyFrame,
                                                                                                   jboolean isConfig) {
    if (muxerPtr == 0 || !directBuffer) {
        return;
    }
    MuxerContext * muxCtx = reinterpret_cast<MuxerContext *>(muxerPtr);

    uint8_t * bufferAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(directBuffer));
    if (!bufferAddress) {
        return;
    }

    std::lock_guard<std::mutex> lock(muxCtx->writeMutex);

    // 🚀 OPRAVA AUDIO THROTTLINGU: Sledujeme, kam až se video dostalo, aby ho Audio nepředběhlo o kilometry
    if (!isConfig) {
        muxCtx->lastVideoPtsUs.store(ptsUs, std::memory_order_relaxed);
    }

    // Pokud je to Config (SPS/PPS), uložíme ho do streamu a zkusíme zapsat hlavičku
    if (isConfig) {
        LOGI("MediaCodec zaslal VIDEO CONFIG (%d bytes). Ukladam jako extradata.", size);
        muxCtx->videoStream->codecpar->extradata = (uint8_t *) av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
        memcpy(muxCtx->videoStream->codecpar->extradata, bufferAddress + offset, size);
        muxCtx->videoStream->codecpar->extradata_size = size;

        muxCtx->videoConfigured = true;
        muxCtx->checkAndWriteHeader();
        return;  // Config packety do finálního souboru nepatří, slouží jen jako hlavičky
    }

    // Bezpečnostní záchrana, kdyby MediaCodec neposlal Config (stává se u softwarových VPU)
    if (!muxCtx->videoConfigured) {
        muxCtx->videoConfigured = true;
        muxCtx->checkAndWriteHeader();
    }

    // Alokace nového FFmpeg paketu a ZKOPÍROVÁNÍ dat (DirectBuffer by v JNI mohl zaniknout)
    AVPacket * pkt = av_packet_alloc();
    av_new_packet(pkt, size);
    memcpy(pkt->data, bufferAddress + offset, size);

    // 🚀 OPRAVA ČASOVÁNÍ PROTI ERROR -22
    // Vynucení striktně rostoucích časů z dodaného ptsUs
    int64_t current_pts = ptsUs;
    if (current_pts <= muxCtx->last_muxed_video_dts) {
        // Ochrana proti "zacyklení" času, pokud MediaCodec pošle duplicitní snímek
        current_pts = muxCtx->last_muxed_video_dts + 1;
    }
    pkt->pts                     = current_pts;
    pkt->dts                     = current_pts;
    muxCtx->last_muxed_video_dts = current_pts;

    pkt->stream_index = muxCtx->videoStream->index;
    if (isKeyFrame) {
        pkt->flags |= AV_PKT_FLAG_KEY;
    }

    // Pokud už je hlavička hotová, zapisujeme. Jinak odkládáme do fronty.
    if (muxCtx->headerWritten) {
        int ret = av_interleaved_write_frame(muxCtx->fmtCtx, pkt);
        if (ret < 0) {
            char err_buf[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(ret, err_buf, AV_ERROR_MAX_STRING_SIZE);
            LOGE("Muxer: CHYBA ZAPISU PAKETU: %d (%s)", ret, err_buf);
        }
        av_packet_free(&pkt);
    } else {
        muxCtx->pendingPackets.push_back(pkt);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llama_media_NativeVideoEngine_nativeProcessAudioToMuxer(JNIEnv * env,
                                                                         jobject,
                                                                         jlong    muxerPtr,
                                                                         jstring  inVideoPathStr,
                                                                         jint     audioStreamIndex,
                                                                         jfloat   volumeGain,
                                                                         jboolean compressor,
                                                                         jboolean ebuR128) {
    if (muxerPtr == 0) {
        return;
    }
    MuxerContext * muxCtx      = reinterpret_cast<MuxerContext *>(muxerPtr);
    const char *   inVideoPath = env->GetStringUTFChars(inVideoPathStr, nullptr);

    LOGI("AudioWorker: Oteviram soubor pro muxing: %s", inVideoPath);

    AVFormatContext * inFmtCtx = nullptr;
    int               fd       = parseProcFdPath(inVideoPath);
    FdAvioOpaque *    opaque   = nullptr;

    if (fd >= 0) {
        inFmtCtx = openFormatFromFd(fd, &opaque);
    } else {
        if (avformat_open_input(&inFmtCtx, inVideoPath, nullptr, nullptr) < 0) {
            inFmtCtx = nullptr;
        }
    }

    if (!inFmtCtx || avformat_find_stream_info(inFmtCtx, nullptr) < 0) {
        LOGE("AudioWorker: Nelze otevrit vstup.");
        if (inFmtCtx) {
            avformat_close_input(&inFmtCtx);
        }
        if (opaque) {
            fdAvioFree(&opaque);
        }
        env->ReleaseStringUTFChars(inVideoPathStr, inVideoPath);
        std::lock_guard<std::mutex> lock(muxCtx->writeMutex);
        muxCtx->audioConfigured = true;
        muxCtx->checkAndWriteHeader();
        return;
    }

    int audioIdx        = -1;
    int currentAudioIdx = 0;
    for (unsigned int i = 0; i < inFmtCtx->nb_streams; i++) {
        if (inFmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            if (currentAudioIdx == audioStreamIndex) {
                audioIdx = i;
                break;
            }
            currentAudioIdx++;
        }
    }
    if (audioIdx < 0) {
        LOGW("AudioWorker: Video nema audio stopu.");
        avformat_close_input(&inFmtCtx);
        if (opaque) {
            fdAvioFree(&opaque);
        }
        env->ReleaseStringUTFChars(inVideoPathStr, inVideoPath);
        std::lock_guard<std::mutex> lock(muxCtx->writeMutex);
        muxCtx->audioConfigured = true;
        muxCtx->checkAndWriteHeader();
        return;
    }

    AVStream *       inStream = inFmtCtx->streams[audioIdx];
    const AVCodec *  decoder  = avcodec_find_decoder(inStream->codecpar->codec_id);
    AVCodecContext * decCtx   = avcodec_alloc_context3(decoder);
    avcodec_parameters_to_context(decCtx, inStream->codecpar);
    avcodec_open2(decCtx, decoder, nullptr);

    // DYNAMICKÁ DETEKCE KODEKU (Respektuje nastavení z Kotlinu nebo 1:1 kontejner)
    AVCodecID       targetCodecId = muxCtx->audioStream->codecpar->codec_id;
    const AVCodec * encoder       = avcodec_find_encoder(targetCodecId);
    if (!encoder) {
        LOGW("AudioWorker: Pozadovany kodek (%d) nenalezen, fallback na AAC.", targetCodecId);
        targetCodecId = AV_CODEC_ID_AAC;
        encoder       = avcodec_find_encoder(targetCodecId);
    }

    AVCodecContext * encCtx = avcodec_alloc_context3(encoder);

    // --- 1:1 KLONOVÁNÍ AUDIO METADAT ---

    // 1. Vzorkovací frekvence (Sample Rate)
    int64_t targetBitrate = decCtx->bit_rate > 0 ? decCtx->bit_rate : 128000;
    if (targetBitrate < 128000) {
        targetBitrate = 128000;  // Bezpecnostni minimum pro AAC
    }
    encCtx->bit_rate    = targetBitrate;
    encCtx->sample_rate = decCtx->sample_rate > 0 ? decCtx->sample_rate : 48000;
#if LIBAVCODEC_VERSION_MAJOR >= 61
    const int * supported_samplerates = nullptr;
    int         num_samplerates       = 0;
    if (avcodec_get_supported_config(encCtx, encoder, AV_CODEC_CONFIG_SAMPLE_RATE, 0,
                                     (const void **) &supported_samplerates, &num_samplerates) >= 0 &&
        supported_samplerates) {
        bool supported = false;
        for (int i = 0; i < num_samplerates; i++) {
            if (supported_samplerates[i] == encCtx->sample_rate) {
                supported = true;
                break;
            }
        }
        if (!supported && num_samplerates > 0) {
            encCtx->sample_rate = supported_samplerates[0];
        }
    }
#else
    if (encoder->supported_samplerates) {
        bool supported = false;
        int  i         = 0;
        while (encoder->supported_samplerates[i]) {
            if (encoder->supported_samplerates[i] == encCtx->sample_rate) {
                supported = true;
                break;
            }
            i++;
        }
        if (!supported) {
            encCtx->sample_rate = encoder->supported_samplerates[0];
        }
    }
#endif

    // 2. Formát vzorků
    encCtx->sample_fmt = AV_SAMPLE_FMT_FLTP;
#if LIBAVCODEC_VERSION_MAJOR >= 61
    const enum AVSampleFormat * sample_fmts     = nullptr;
    int                         num_sample_fmts = 0;
    if (avcodec_get_supported_config(encCtx, encoder, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0, (const void **) &sample_fmts,
                                     &num_sample_fmts) >= 0 &&
        sample_fmts && num_sample_fmts > 0) {
        encCtx->sample_fmt = sample_fmts[0];
    }
#else
    if (encoder->sample_fmts) {
        encCtx->sample_fmt = encoder->sample_fmts[0];
    }
#endif

    // 3. Bitrate (Důležité pro zachování kvality!)
    //
    //int64_t orig_bitrate = inStream->codecpar->bit_rate;
    //if (orig_bitrate <= 0) {
    //    orig_bitrate = decCtx->bit_rate;
    //}
    //if (orig_bitrate <= 0) {
    //    orig_bitrate = 256000;
    //}
    //ncCtx->bit_rate = orig_bitrate;

    // 🚀 OPRAVA: Odstraněn if (orig_bitrate), dáváme čistých 320 kbps
    encCtx->bit_rate    = 320000;
    encCtx->sample_rate = 48000;
    encCtx->time_base   = { 1, encCtx->sample_rate };

    // 4. Kanály (Stereo/Mono/Surround)
#if LIBAVCODEC_VERSION_MAJOR >= 59
    if (decCtx->ch_layout.nb_channels > 0) {
        av_channel_layout_copy(&encCtx->ch_layout, &decCtx->ch_layout);
    } else {
        av_channel_layout_default(&encCtx->ch_layout, 2);
    }
#else
    if (decCtx->channels > 0) {
        encCtx->channels = decCtx->channels;
        encCtx->channel_layout =
            decCtx->channel_layout ? decCtx->channel_layout : av_get_default_channel_layout(decCtx->channels);
    } else {
        encCtx->channels       = 2;
        encCtx->channel_layout = AV_CH_LAYOUT_STEREO;
    }
#endif

    if (muxCtx->fmtCtx->oformat->flags & AVFMT_GLOBALHEADER) {
        encCtx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }

    if (avcodec_open2(encCtx, encoder, nullptr) < 0) {
        LOGE("AudioWorker: Nelze otevrit dynamicky audio encoder!");
        avcodec_free_context(&encCtx);
        avcodec_free_context(&decCtx);
        avformat_close_input(&inFmtCtx);
        if (opaque) {
            fdAvioFree(&opaque);
        }
        env->ReleaseStringUTFChars(inVideoPathStr, inVideoPath);
        std::lock_guard<std::mutex> lock(muxCtx->writeMutex);
        muxCtx->audioConfigured = true;
        muxCtx->checkAndWriteHeader();
        return;
    }

    int64_t next_audio_pts = 0;

    // SYNCHRONIZACE METADAT
    avcodec_parameters_from_context(muxCtx->audioStream->codecpar, encCtx);
    {
        std::lock_guard<std::mutex> lock(muxCtx->writeMutex);
        muxCtx->audioStream->time_base = encCtx->time_base;  // Klíčové pro MKV/MP4 PTS
        muxCtx->audioConfigured        = true;
        muxCtx->checkAndWriteHeader();
    }

    // --- 🚀 AVFILTERGRAPH (DSP + Resampling) ---
    AVFilterGraph *   filter_graph   = avfilter_graph_alloc();
    AVFilterContext * buffersrc_ctx  = nullptr;
    AVFilterContext * buffersink_ctx = nullptr;

    char args[512];
#if LIBAVCODEC_VERSION_MAJOR >= 59
    char ch_layout_str[64];
    av_channel_layout_describe(&decCtx->ch_layout, ch_layout_str, sizeof(ch_layout_str));
    snprintf(args, sizeof(args), "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s", 1,
             decCtx->sample_rate, decCtx->sample_rate, av_get_sample_fmt_name(decCtx->sample_fmt), ch_layout_str);
#else
    snprintf(args, sizeof(args), "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=0x%" PRIx64, 1,
             decCtx->sample_rate, decCtx->sample_rate, av_get_sample_fmt_name(decCtx->sample_fmt),
             (uint64_t) decCtx->channel_layout);
#endif

    const AVFilter * abuffersrc  = avfilter_get_by_name("abuffer");
    const AVFilter * abuffersink = avfilter_get_by_name("abuffersink");

    avfilter_graph_create_filter(&buffersrc_ctx, abuffersrc, "in", args, nullptr, filter_graph);
    avfilter_graph_create_filter(&buffersink_ctx, abuffersink, "out", nullptr, nullptr, filter_graph);

    std::string filter_str = "anull";
    if (volumeGain != 1.0f) {
        char volStr[32];
        // Použití snprintf ignoruje systémové Locale a garantuje formát s tečkou
        snprintf(volStr, sizeof(volStr), "volume=%.2f", volumeGain);
        filter_str += "," + std::string(volStr);
    }
    if (compressor) {
        filter_str += ",acompressor";
    }
    if (ebuR128) {
        filter_str += ",loudnorm=I=-23:LRA=7:TP=-2";
    }

    char out_ch_layout_str[64];
#if LIBAVCODEC_VERSION_MAJOR >= 59
    av_channel_layout_describe(&encCtx->ch_layout, out_ch_layout_str, sizeof(out_ch_layout_str));
#else
    av_get_channel_layout_string(out_ch_layout_str, sizeof(out_ch_layout_str), encCtx->channels,
                                 encCtx->channel_layout);
#endif

    filter_str += ",aformat=sample_fmts=";
    filter_str += av_get_sample_fmt_name(encCtx->sample_fmt);
    filter_str += ":sample_rates=";
    filter_str += std::to_string(encCtx->sample_rate);
    filter_str += ":channel_layouts=";
    filter_str += out_ch_layout_str;

    filter_str = filter_str.substr(6);

    AVFilterInOut * outputs = avfilter_inout_alloc();
    AVFilterInOut * inputs  = avfilter_inout_alloc();
    outputs->name           = av_strdup("in");
    outputs->filter_ctx     = buffersrc_ctx;
    outputs->pad_idx        = 0;
    outputs->next           = nullptr;
    inputs->name            = av_strdup("out");
    inputs->filter_ctx      = buffersink_ctx;
    inputs->pad_idx         = 0;
    inputs->next            = nullptr;

    if (avfilter_graph_parse_ptr(filter_graph, filter_str.c_str(), &inputs, &outputs, nullptr) < 0) {
        LOGE("AudioWorker: Nelze parsovat filter graf!");
        avfilter_inout_free(&inputs);
        avfilter_inout_free(&outputs);
        avfilter_graph_free(&filter_graph);
        avcodec_free_context(&encCtx);
        avcodec_free_context(&decCtx);
        avformat_close_input(&inFmtCtx);
        if (opaque) {
            fdAvioFree(&opaque);
        }
        env->ReleaseStringUTFChars(inVideoPathStr, inVideoPath);
        std::lock_guard<std::mutex> lock(muxCtx->writeMutex);
        muxCtx->audioConfigured = true;
        muxCtx->checkAndWriteHeader();
        return;
    }
    if (avfilter_graph_config(filter_graph, nullptr) < 0) {
        LOGE("AudioWorker: Nelze konfigurovat filter graf!");
        avfilter_inout_free(&inputs);
        avfilter_inout_free(&outputs);
        return;
    }
    avfilter_inout_free(&inputs);
    avfilter_inout_free(&outputs);

    // 🚀 DYNAMICKÁ AUDIO FIFO PAMĚŤ: Garantuje, že jakýkoliv kodek (AC3, AAC atd.) dostane přesně tolik vzorků, kolik vyžaduje.
    AVAudioFifo * audioFifo = av_audio_fifo_alloc(encCtx->sample_fmt,
#if LIBAVCODEC_VERSION_MAJOR >= 59
                                                  encCtx->ch_layout.nb_channels,
#else
                                                  encCtx->channels,
#endif
                                                  1);

    AVPacket * inPkt    = av_packet_alloc();
    AVPacket * outPkt   = av_packet_alloc();
    AVFrame *  frame    = av_frame_alloc();
    AVFrame *  resFrame = av_frame_alloc();

    while (av_read_frame(inFmtCtx, inPkt) >= 0) {
        if (g_isCancelled.load(std::memory_order_acquire)) {
            break;
        }
        if (inPkt->stream_index == audioIdx) {
            if (avcodec_send_packet(decCtx, inPkt) == 0) {
                while (avcodec_receive_frame(decCtx, frame) == 0) {
                    if (av_buffersrc_add_frame_flags(buffersrc_ctx, frame, AV_BUFFERSRC_FLAG_KEEP_REF) >= 0) {
                        int written_to_fifo = 0;
                        while (av_buffersink_get_frame(buffersink_ctx, resFrame) >= 0) {
                            av_audio_fifo_write(audioFifo, (void **) resFrame->data, resFrame->nb_samples);
                            written_to_fifo = 1;
                            av_frame_unref(resFrame);
                        }
                        if (written_to_fifo) {
                            // Omezovač Audio-Předběhnutí (Throttling) z původní verze
                            int64_t currentAudioUs = av_rescale_q(next_audio_pts, encCtx->time_base, { 1, 1000000 });
                            while (!g_isCancelled.load(std::memory_order_acquire) &&
                                   !muxCtx->videoEof.load(std::memory_order_acquire)) {
                                int64_t vidUs = muxCtx->lastVideoPtsUs.load(std::memory_order_relaxed);
                                if (currentAudioUs <= vidUs + 1000000LL) {
                                    break;
                                }
                                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                            }

                            // Dynamická detekce velikosti framu (AC3 potřebuje 1536, AAC 1024, jiné 0 - dynamické)
                            int frame_size = (encCtx->frame_size == 0) ? 1024 : encCtx->frame_size;

                            // Z FIFO krájíme přesné kusy a sypeme do enkodéru
                            while (av_audio_fifo_size(audioFifo) >= frame_size) {
                                AVFrame * encFrame    = av_frame_alloc();
                                encFrame->nb_samples  = frame_size;
                                encFrame->format      = encCtx->sample_fmt;
                                encFrame->sample_rate = encCtx->sample_rate;
#if LIBAVCODEC_VERSION_MAJOR >= 59
                                av_channel_layout_copy(&encFrame->ch_layout, &encCtx->ch_layout);
#else
                                encFrame->channel_layout = encCtx->channel_layout;
                                encFrame->channels       = encCtx->channels;
#endif
                                av_frame_get_buffer(encFrame, 0);

                                av_audio_fifo_read(audioFifo, (void **) encFrame->data, frame_size);
                                encFrame->pts = next_audio_pts;
                                next_audio_pts += frame_size;

                                if (avcodec_send_frame(encCtx, encFrame) == 0) {
                                    while (avcodec_receive_packet(encCtx, outPkt) == 0) {
                                        outPkt->stream_index = muxCtx->audioStream->index;
                                        av_packet_rescale_ts(outPkt, encCtx->time_base, muxCtx->audioStream->time_base);
                                        {
                                            std::lock_guard<std::mutex> lock(muxCtx->writeMutex);
                                            if (muxCtx->headerWritten) {
                                                av_interleaved_write_frame(muxCtx->fmtCtx, outPkt);
                                            } else {
                                                muxCtx->pendingPackets.push_back(av_packet_clone(outPkt));
                                            }
                                        }
                                        av_packet_unref(outPkt);
                                    }
                                }
                                av_frame_free(&encFrame);
                            }
                        }
                    }
                }
            }
        }
        av_packet_unref(inPkt);
    }

    // FLUSH FIFO a kodéru na konci streamu
    while (av_audio_fifo_size(audioFifo) > 0) {
        int remaining  = av_audio_fifo_size(audioFifo);
        int frame_size = (encCtx->frame_size == 0) ? 1024 : encCtx->frame_size;
        int chunk      = std::min(remaining, frame_size);

        AVFrame * encFrame    = av_frame_alloc();
        encFrame->nb_samples  = chunk;
        encFrame->format      = encCtx->sample_fmt;
        encFrame->sample_rate = encCtx->sample_rate;
#if LIBAVCODEC_VERSION_MAJOR >= 59
        av_channel_layout_copy(&encFrame->ch_layout, &encCtx->ch_layout);
#else
        encFrame->channel_layout = encCtx->channel_layout;
        encFrame->channels       = encCtx->channels;
#endif
        av_frame_get_buffer(encFrame, 0);

        av_audio_fifo_read(audioFifo, (void **) encFrame->data, chunk);
        encFrame->pts = next_audio_pts;
        next_audio_pts += chunk;

        if (avcodec_send_frame(encCtx, encFrame) == 0) {
            while (avcodec_receive_packet(encCtx, outPkt) == 0) {
                outPkt->stream_index = muxCtx->audioStream->index;
                av_packet_rescale_ts(outPkt, encCtx->time_base, muxCtx->audioStream->time_base);
                {
                    std::lock_guard<std::mutex> lock(muxCtx->writeMutex);
                    if (muxCtx->headerWritten) {
                        av_interleaved_write_frame(muxCtx->fmtCtx, outPkt);
                    } else {
                        muxCtx->pendingPackets.push_back(av_packet_clone(outPkt));
                    }
                }
                av_packet_unref(outPkt);
            }
        }
        av_frame_free(&encFrame);
    }

    avcodec_send_frame(encCtx, nullptr);
    while (avcodec_receive_packet(encCtx, outPkt) == 0) {
        outPkt->stream_index = muxCtx->audioStream->index;
        av_packet_rescale_ts(outPkt, encCtx->time_base, muxCtx->audioStream->time_base);
        {
            std::lock_guard<std::mutex> lock(muxCtx->writeMutex);
            if (muxCtx->headerWritten) {
                av_interleaved_write_frame(muxCtx->fmtCtx, outPkt);
            } else {
                muxCtx->pendingPackets.push_back(av_packet_clone(outPkt));
            }
        }
        av_packet_unref(outPkt);
    }

    // Uvolnění paměti (Strict No-Leak Policy)
    av_audio_fifo_free(audioFifo);
    av_frame_free(&frame);
    av_frame_free(&resFrame);
    av_packet_free(&inPkt);
    av_packet_free(&outPkt);
    avfilter_graph_free(&filter_graph);
    avcodec_free_context(&encCtx);
    avcodec_free_context(&decCtx);
    avformat_close_input(&inFmtCtx);
    if (opaque) {
        fdAvioFree(&opaque);
    }
    env->ReleaseStringUTFChars(inVideoPathStr, inVideoPath);
    LOGI("AudioWorker: Dokonceno dynamicke paralelni kodovani zvuku.");
}

extern "C" JNIEXPORT void JNICALL Java_com_example_llama_media_NativeVideoEngine_nativeCloseMuxer(JNIEnv * env,
                                                                                                  jobject,
                                                                                                  jlong muxerPtr) {
    if (muxerPtr == 0) {
        return;
    }
    MuxerContext * muxCtx = reinterpret_cast<MuxerContext *>(muxerPtr);

    // 🚀 OPRAVA: Trailer se smí zapsat POUZE pokud se úspěšně zapsala hlavička!
    if (muxCtx->headerWritten) {
        av_write_trailer(muxCtx->fmtCtx);
    }

    if (!(muxCtx->fmtCtx->oformat->flags & AVFMT_NOFILE)) {
        avio_closep(&muxCtx->fmtCtx->pb);
    }
    avformat_free_context(muxCtx->fmtCtx);

    delete muxCtx;
    LOGI("Muxer uspesne uzavren a pamat uvolnena.");
}

extern "C" JNIEXPORT void JNICALL Java_com_example_llama_media_NativeVideoEngine_nativeSignalVideoEof(JNIEnv * env,
                                                                                                      jobject,
                                                                                                      jlong muxerPtr) {
    if (muxerPtr != 0) {
        reinterpret_cast<MuxerContext *>(muxerPtr)->videoEof.store(true, std::memory_order_release);
    }
}

// ==========================================================================
// LEGACY NATIVE BURN SUBTITLES (DÁLNICE B)
// ==========================================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_example_llama_media_NativeVideoEngine_nativeBurnSubtitles(JNIEnv * env,
                                                                   jobject,
                                                                   jstring  videoUriStr,
                                                                   jstring  outFilePathStr,
                                                                   jobject  listener,
                                                                   jobject  frameProvider,
                                                                   jobject  jBitmap,
                                                                   jboolean useHardware,
                                                                   jint     codecIndex,
                                                                   jint     qualityLevel,
                                                                   jint     audioStreamIndex,
                                                                   jint     threadCount,
                                                                   jstring  presetStr) {
    if (!videoUriStr || !outFilePathStr) {
        LOGE("nullptr pro povinne parametry.");
        return -1;
    }

    const char * c_vid = env->GetStringUTFChars(videoUriStr, nullptr);
    const char * c_out = env->GetStringUTFChars(outFilePathStr, nullptr);

    if (!c_vid || !c_out) {
        if (c_vid) {
            env->ReleaseStringUTFChars(videoUriStr, c_vid);
        }
        if (c_out) {
            env->ReleaseStringUTFChars(outFilePathStr, c_out);
        }
        return -1;
    }

    std::string videoPath(c_vid);
    std::string outPath(c_out);

    std::string preset = "veryfast";
    if (presetStr) {
        const char * c_preset = env->GetStringUTFChars(presetStr, nullptr);
        if (c_preset) {
            preset = std::string(c_preset);
            env->ReleaseStringUTFChars(presetStr, c_preset);
        }
    }

    env->ReleaseStringUTFChars(videoUriStr, c_vid);
    env->ReleaseStringUTFChars(outFilePathStr, c_out);

    g_isCancelled.store(false, std::memory_order_release);
    LOGI("nativeBurnSubtitles v5 WYSIWYG START | HW=%s | %s -> %s | threads=%d | preset=%s", useHardware ? "ANO" : "NE", videoPath.c_str(),
         outPath.c_str(), threadCount, preset.c_str());

    // --- JNI method IDs pro SubtitleFrameProvider callback ---
    jmethodID renderMethodId = nullptr;
    if (frameProvider && jBitmap) {
        jclass providerClass = env->GetObjectClass(frameProvider);
        renderMethodId       = env->GetMethodID(providerClass, "renderFrame", "(JLandroid/graphics/Bitmap;)V");
        env->DeleteLocalRef(providerClass);
        if (!renderMethodId) {
            LOGW("renderFrame metoda nenalezena v SubtitleFrameProvider.");
            env->ExceptionClear();
        }
    }

    // Progress callback
    jmethodID onProgressMethod = nullptr;
    if (listener) {
        jclass cls       = env->GetObjectClass(listener);
        onProgressMethod = env->GetMethodID(cls, "onProgress", "(F)V");
        env->DeleteLocalRef(cls);
        if (!onProgressMethod) {
            LOGW("onProgress nenalezena.");
            env->ExceptionClear();
        }
    }

    AVFormatContext * inputFmtCtx     = nullptr;
    AVFormatContext * outputFmtCtx    = nullptr;
    AVCodecContext *  decoderCtx      = nullptr;
    AVCodecContext *  encoderCtx      = nullptr;
    AVFilterGraph *   filterGraph     = nullptr;
    AVFilterContext * buffersinkCtx   = nullptr;
    AVFilterContext * buffersrcCtx    = nullptr;  // Hlavní video
    AVFilterContext * buffersrcSubCtx = nullptr;  // WYSIWYG Bitmap overlay
    FdAvioOpaque *    fdOpaque        = nullptr;

    int audioStreamIdx = -1;
    int resultCode     = 0;

    if (videoPath.empty()) {
        LOGE("Kriticka chyba: videoPath je prazdna!");
        return -1;
    }

    try {
        avformat_network_init();

        // --- Otevrit vstup ---
        int procFd = parseProcFdPath(videoPath);
        if (procFd >= 0) {
            // openFormatFromFd() interně nastavuje ctx->pb (custom AVIO) a volá avformat_open_input().
            // NESMÍ se volat avformat_open_input() znovu — double open → AVERROR(EINVAL) nebo memory corruption.
            inputFmtCtx = openFormatFromFd(procFd, &fdOpaque);
            if (!inputFmtCtx) {
                throw std::runtime_error("OOM AVIO context.");
            }
            // AVFMT_FLAG_CUSTOM_IO informuje muxer, že I/O je náš custom AVIO kontext
            inputFmtCtx->flags |= AVFMT_FLAG_CUSTOM_IO;
        } else {
            if (avformat_open_input(&inputFmtCtx, videoPath.c_str(), nullptr, nullptr) < 0) {
                throw std::runtime_error("avformat_open_input (path) selhal.");
            }
        }

        if (avformat_find_stream_info(inputFmtCtx, nullptr) < 0) {
            throw std::runtime_error("find_stream_info selhal.");
        }

        LOGI("Streamy: %u, delka: %" PRId64 " us", inputFmtCtx->nb_streams, inputFmtCtx->duration);

        int videoStreamIdx = av_find_best_stream(inputFmtCtx, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        if (videoStreamIdx < 0) {
            throw std::runtime_error("Video stream nenalezen.");
        }
        audioStreamIdx      = -1;
        int currentAudioIdx = 0;
        for (unsigned int i = 0; i < inputFmtCtx->nb_streams; i++) {
            if (inputFmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
                if (currentAudioIdx == audioStreamIndex) {
                    audioStreamIdx = i;
                    break;
                }
                currentAudioIdx++;
            }
        }

        AVStream * inVideoStream = inputFmtCtx->streams[videoStreamIdx];
        AVStream * inAudioStream = (audioStreamIdx >= 0) ? inputFmtCtx->streams[audioStreamIdx] : nullptr;

        // --- Output context ---
        // FFmpeg automaticky rozpozná kontejner (MP4, MKV, MOV, WEBM atd.) podle koncovky v outPath
        if (avformat_alloc_output_context2(&outputFmtCtx, nullptr, nullptr, outPath.c_str()) < 0) {
            throw std::runtime_error("alloc_output_context2 selhal při auto-detekci kontejneru.");
        }

        AVStream * outVideoStream = avformat_new_stream(outputFmtCtx, nullptr);
        if (!outVideoStream) {
            throw std::runtime_error("Nelze vytvorit output video stream.");
        }

        // Audio stream creation
        AVStream * outAudioStream = nullptr;
        if (inAudioStream) {
            outAudioStream = avformat_new_stream(outputFmtCtx, nullptr);
            if (!outAudioStream) {
                throw std::runtime_error("Nelze vytvorit audio stream v kontejneru.");
            }
            if (avcodec_parameters_copy(outAudioStream->codecpar, inAudioStream->codecpar) < 0) {
                throw std::runtime_error("Nelze zkopirovat parametry audio streamu.");
            }
            outAudioStream->codecpar->codec_tag = 0;  // Prevence MP4 strict mode chyb
            outAudioStream->time_base           = inAudioStream->time_base;
        }

        // ===================================================================
        // RE-ENCODE MODE (subtitle burn-in)
        // ===================================================================
        LOGI("=== RE-ENCODE MODE (HW/SW + libass titulky) ===");

        // Dekoder - EXPLICITNÍ HLEDÁNÍ HW DEKODÉRU
        const AVCodec * decoder = nullptr;
        if (useHardware) {
            std::string hwDecName = "";
            switch (inVideoStream->codecpar->codec_id) {
                case AV_CODEC_ID_H264: hwDecName = "h264_mediacodec"; break;
                case AV_CODEC_ID_HEVC: hwDecName = "hevc_mediacodec"; break;
                case AV_CODEC_ID_VP9:  hwDecName = "vp9_mediacodec"; break;
                case AV_CODEC_ID_VP8:  hwDecName = "vp8_mediacodec"; break;
                case AV_CODEC_ID_MPEG4: hwDecName = "mpeg4_mediacodec"; break;
                default: break;
            }
            if (!hwDecName.empty()) {
                decoder = avcodec_find_decoder_by_name(hwDecName.c_str());
                if (decoder) {
                    LOGI("Nalezen explicitní HW dekodér: %s", hwDecName.c_str());
                } else {
                    LOGW("Explicitní HW dekodér %s nenalezen, fallback na SW.", hwDecName.c_str());
                }
            }
        }
        
        if (!decoder) {
            decoder = avcodec_find_decoder(inVideoStream->codecpar->codec_id);
            if (!decoder) {
                throw std::runtime_error("Dekoder nenalezen.");
            }
            LOGI("Používám dekodér: %s", decoder->name);
        }

        decoderCtx = avcodec_alloc_context3(decoder);
        if (!decoderCtx) {
            throw std::runtime_error("OOM decoderCtx.");
        }
        if (avcodec_parameters_to_context(decoderCtx, inVideoStream->codecpar) < 0) {
            throw std::runtime_error("parameters_to_context selhal.");
        }
        // Multithreaded SW decode — vsechna jadra ARM64
        decoderCtx->thread_count = 0;
        decoderCtx->thread_type  = FF_THREAD_FRAME | FF_THREAD_SLICE;

        // HW dekodér — volitelný přes UI přepínač (default=true)
        ScopedAVBufferRef hwDeviceCtx(nullptr);
        bool usingHwDecodeTransfer = false;
        if (useHardware) {
            AVBufferRef * hwRef = createHardwareDeviceContext();
            if (hwRef) {
                hwDeviceCtx.reset(hwRef);
                // Přiřazení HW kontextu dekodéru — FFmpeg použije MediaCodec VPU
                decoderCtx->hw_device_ctx = av_buffer_ref(hwDeviceCtx.get());
                usingHwDecodeTransfer = true;
                LOGI("HW dekoder: MediaCodec VPU aktivovan (+ transfer do CPU).");
            } else {
                LOGW("HW dekoder: Inicializace selhala — fallback na SW decode.");
            }
        } else {
            LOGI("SW dekoder: Uzivatel vybral softwarovy dekoder.");
        }

        if (avcodec_open2(decoderCtx, decoder, nullptr) < 0) {
            throw std::runtime_error("avcodec_open2 (decoder) selhal.");
        }

        // Encoder probe — respektuje volby z UI
        EncoderSelection encSel = probeVideoEncoder(useHardware, codecIndex);
        LOGI("Encoder: %s (HW=%s)", encSel.name, encSel.isHardware ? "ANO" : "NE");

        // ==========================================================================
        // WYSIWYG OVERLAY FILTER GRAPH
        // ==========================================================================
        AVRational framerateSrc = inVideoStream->avg_frame_rate;
        if (framerateSrc.num <= 0 || framerateSrc.den <= 0) {
            framerateSrc = { 30, 1 };
        }

        // --- OBNOVENÍ FFmpeg OVERLAY FILTRU PRO DÁLNICI B ---
        filterGraph = avfilter_graph_alloc();
        if (!filterGraph) {
            throw std::runtime_error("OOM filterGraph.");
        }

        // 1. Hlavní video vstup
        // 🚀 HW FIX: Pokud používáme MediaCodec s HW transferem, snímky jdoucí do filtru 
        // budou ve formátu NV12, nikoliv MEDIACODEC.
        int srcPixFmt = decoderCtx->pix_fmt;
        if (usingHwDecodeTransfer && (srcPixFmt == AV_PIX_FMT_MEDIACODEC || srcPixFmt == AV_PIX_FMT_NONE)) {
            srcPixFmt = AV_PIX_FMT_NV12; // Výchozí formát stažený z MediaCodec
            LOGI("Nastavení vstupního formátu filtru na NV12 (HW transfer)");
        }

        char srcArgs[512];
        snprintf(srcArgs, sizeof(srcArgs),
                 "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=%d/%d:frame_rate=%d/%d", decoderCtx->width,
                 decoderCtx->height, srcPixFmt, inVideoStream->time_base.num, inVideoStream->time_base.den,
                 decoderCtx->sample_aspect_ratio.num ? decoderCtx->sample_aspect_ratio.num : 1,
                 decoderCtx->sample_aspect_ratio.den ? decoderCtx->sample_aspect_ratio.den : 1, framerateSrc.num,
                 framerateSrc.den);

        if (avfilter_graph_create_filter(&buffersrcCtx, avfilter_get_by_name("buffer"), "in_video", srcArgs, nullptr,
                                         filterGraph) < 0) {
            throw std::runtime_error("buffersrc (video) create_filter selhal.");
        }

        if (avfilter_graph_create_filter(&buffersinkCtx, avfilter_get_by_name("buffersink"), "out", nullptr, nullptr,
                                         filterGraph) < 0) {
            throw std::runtime_error("buffersink create_filter selhal.");
        }

        if (encSel.isHardware) {
            // 🚀 HW FIX 1: Qualcomm striktně vyžaduje NV12 z CPU paměti!
            const AVPixelFormat hw_fmts[] = { AV_PIX_FMT_NV12 };
            av_opt_set_bin(buffersinkCtx, "pix_fmts", (const uint8_t *) hw_fmts, sizeof(hw_fmts),
                           AV_OPT_SEARCH_CHILDREN);
        } else {
            const AVPixelFormat sw_fmts[] = { AV_PIX_FMT_YUV420P };
            av_opt_set_bin(buffersinkCtx, "pix_fmts", (const uint8_t *) sw_fmts, sizeof(sw_fmts),
                           AV_OPT_SEARCH_CHILDREN);
        }

        // 2. Overlay vstup (Kotlin Bitmap)
        bool useOverlay = (frameProvider != nullptr && jBitmap != nullptr && renderMethodId != nullptr);
        if (useOverlay) {
            AndroidBitmapInfo initBmpInfo;
            if (AndroidBitmap_getInfo(env, jBitmap, &initBmpInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
                throw std::runtime_error("Nelze precist info o Bitmap pro inicializaci filtru.");
            }

            char subArgs[512];
            snprintf(subArgs, sizeof(subArgs),
                     "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=1/1:frame_rate=%d/%d",
                     (int) initBmpInfo.width, (int) initBmpInfo.height, AV_PIX_FMT_RGBA, inVideoStream->time_base.num,
                     inVideoStream->time_base.den, framerateSrc.num, framerateSrc.den);

            if (avfilter_graph_create_filter(&buffersrcSubCtx, avfilter_get_by_name("buffer"), "in_sub", subArgs,
                                             nullptr, filterGraph) < 0) {
                throw std::runtime_error("buffersrc (subtitle) create_filter selhal.");
            }
        }

        // 3. Propojení grafu + scale filtr pro HW 16px macroblock alignment
        int targetW = decoderCtx->width;
        int targetH = decoderCtx->height;
        if (encSel.isHardware) {
            targetW -= targetW % 16;
            targetH -= targetH % 16;
        } else {
            targetW -= targetW % 2;
            targetH -= targetH % 2;
        }

        char filterSpec[1024];
        if (useOverlay) {
            snprintf(filterSpec, sizeof(filterSpec),
                     "[in_video][in_sub]overlay=format=auto:eof_action=pass,scale=%d:%d[out]", targetW, targetH);
        } else {
            snprintf(filterSpec, sizeof(filterSpec), "[in_video]scale=%d:%d[out]", targetW, targetH);
        }
        LOGI("FilterGraph: %s | target=%dx%d", filterSpec, targetW, targetH);

        AVFilterInOut * outputs = avfilter_inout_alloc();
        if (!outputs) {
            throw std::runtime_error("OOM avfilter_inout (video).");
        }
        outputs->name       = av_strdup("in_video");
        outputs->filter_ctx = buffersrcCtx;
        outputs->pad_idx    = 0;
        outputs->next       = nullptr;

        if (useOverlay) {
            AVFilterInOut * outputsSub = avfilter_inout_alloc();
            if (!outputsSub) {
                avfilter_inout_free(&outputs);
                throw std::runtime_error("OOM avfilter_inout (sub).");
            }
            outputsSub->name       = av_strdup("in_sub");
            outputsSub->filter_ctx = buffersrcSubCtx;
            outputsSub->pad_idx    = 0;
            outputsSub->next       = nullptr;
            outputs->next          = outputsSub;
        }

        AVFilterInOut * inputs = avfilter_inout_alloc();
        if (!inputs) {
            avfilter_inout_free(&outputs);
            throw std::runtime_error("OOM avfilter_inout (out).");
        }
        inputs->name       = av_strdup("out");
        inputs->filter_ctx = buffersinkCtx;
        inputs->pad_idx    = 0;
        inputs->next       = nullptr;

        int ret = avfilter_graph_parse_ptr(filterGraph, filterSpec, &inputs, &outputs, nullptr);
        avfilter_inout_free(&outputs);
        avfilter_inout_free(&inputs);
        if (ret < 0) {
            throw std::runtime_error("avfilter_graph_parse_ptr selhal: " + ffmpegError(ret));
        }
        if (avfilter_graph_config(filterGraph, nullptr) < 0) {
            throw std::runtime_error("avfilter_graph_config selhal.");
        }
        // Konec konfigurace filtru

        // =====================================================================
        // ENCODER CONTEXT (STRIKTNÍ HW KONFIGURACE - ŽÁDNÉ FALLBACKY)
        // =====================================================================
        encoderCtx = avcodec_alloc_context3(encSel.codec);
        if (!encoderCtx) {
            throw std::runtime_error("OOM encoderCtx.");
        }

        // Získáme rozměry z dekodéru, protože filterGraph už neexistuje
        encoderCtx->width               = decoderCtx->width;
        encoderCtx->height              = decoderCtx->height;
        encoderCtx->sample_aspect_ratio = decoderCtx->sample_aspect_ratio;
        
        // OPRAVA (AV1 zelený obraz + deprecated warning): 
        // Moderní způsob získání podporovaných formátů přes avcodec_get_supported_config
        const enum AVPixelFormat *supported_pix_fmts = nullptr;
        avcodec_get_supported_config(nullptr, encSel.codec, AV_CODEC_CONFIG_PIX_FORMAT, 0, (const void**)&supported_pix_fmts, nullptr);

        if (supported_pix_fmts && supported_pix_fmts[0] != AV_PIX_FMT_NONE) {
            encoderCtx->pix_fmt = supported_pix_fmts[0];
            // Pro mediacodec preferujeme nv12, pokud je v seznamu podporovaných
            for (int i = 0; supported_pix_fmts[i] != AV_PIX_FMT_NONE; i++) {
                if (supported_pix_fmts[i] == AV_PIX_FMT_NV12 && encSel.isHardware) {
                    encoderCtx->pix_fmt = AV_PIX_FMT_NV12;
                    break;
                }
            }
        } else {
            encoderCtx->pix_fmt = encSel.isHardware ? AV_PIX_FMT_NV12 : AV_PIX_FMT_YUV420P;
        }
        LOGI("Encoder config: %dx%d, pix_fmt: %d", encoderCtx->width, encoderCtx->height, encoderCtx->pix_fmt);

        // INTELIGENTNÍ BITRATE
        int64_t sourceBitrate = inputFmtCtx->bit_rate;
        if (sourceBitrate <= 0 && inVideoStream->codecpar->bit_rate > 0) {
            sourceBitrate = inVideoStream->codecpar->bit_rate;
        }
        if (sourceBitrate <= 0) {
            // Dynamický výpočet fallbacku: width * height * fps * bits_per_pixel (např. 0.1)
            double fps = 30.0;
            if (inVideoStream->avg_frame_rate.den > 0) {
                fps = (double) inVideoStream->avg_frame_rate.num / inVideoStream->avg_frame_rate.den;
            }
            sourceBitrate = (int64_t) (encoderCtx->width * encoderCtx->height * fps * 0.1);
            if (sourceBitrate <= 0) {
                sourceBitrate = 5000000LL;  // Ultimate fallback
            }
        }

        AVDictionary * encOpts = nullptr;

        if (encSel.isHardware) {
            int64_t hwBitrate = sourceBitrate;
            if (qualityLevel == 0) {
                hwBitrate = (int64_t) (sourceBitrate * 1.5);
            } else if (qualityLevel == 2) {
                hwBitrate = (int64_t) (sourceBitrate * 0.7);
            }

            encoderCtx->bit_rate = hwBitrate;

            // 🚀 HW FIX 2: Profile "main" garantuje kompatibilitu.
            av_dict_set(&encOpts, "profile", "main", 0);
            av_opt_set(encoderCtx->priv_data, "preset", preset.c_str(), 0);
            LOGI("Kvalita (HW): Bitrate %" PRId64 " bps", hwBitrate);
        } else {
            const char * crfVal = "18";
            if (qualityLevel == 1) {
                crfVal = "23";
            } else if (qualityLevel == 2) {
                crfVal = "28";
            }
            av_opt_set(encoderCtx->priv_data, "preset", "fast", 0);
            av_opt_set(encoderCtx->priv_data, "crf", crfVal, 0);
        }

        // Použijeme framerate ze vstupu, protože buffersinkCtx neexistuje
        AVRational framerate = framerateSrc;
        if (framerate.num <= 0 || framerate.den <= 0) {
            framerate = { 30, 1 };
        }

        // 🚀 HW FIX 3: Omezení framerate. Šílené FPS z mobilů způsobí crash GPU kodéru.
        if (av_q2d(framerate) > 65.0) {
            LOGW("Framerate prilis vysoky pro HW (%.2f). Zastropovano na 60fps.", av_q2d(framerate));
            framerate = { 60, 1 };
        }

        encoderCtx->framerate     = framerate;
        encoderCtx->time_base     = av_inv_q(framerate);
        outVideoStream->time_base = encoderCtx->time_base;
        outVideoStream->avg_frame_rate = framerate; // OPRAVA (MKV 0.30 fps): Nastavíme explicitně FPS pro Muxer
        outVideoStream->r_frame_rate = framerate;

        // 🚀 HW FIX 4: Explicitní barvy. Neznámé hodnoty GPU nesnáší.
        encoderCtx->color_range     = AVCOL_RANGE_JPEG;  // PC/Full range zaručuje ostré UI titulky
        encoderCtx->colorspace      = AVCOL_SPC_BT709;
        encoderCtx->color_primaries = AVCOL_PRI_BT709;
        encoderCtx->color_trc       = AVCOL_TRC_BT709;

        if (outputFmtCtx->oformat->flags & AVFMT_GLOBALHEADER) {
            encoderCtx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        }

        encoderCtx->gop_size = 60;
        if (!encSel.isHardware) {
            encoderCtx->thread_count = threadCount > 0 ? threadCount : 0;
            encoderCtx->thread_type  = FF_THREAD_FRAME;

            if (threadCount >= 0) {
                av_dict_set(&encOpts, "threads", std::to_string(threadCount).c_str(), 0);
                LOGI("FFmpeg Threads nastaveno na: %d", threadCount);
            }
            av_dict_set(&encOpts, "preset", preset.c_str(), 0);
            av_dict_set(&encOpts, "tune", "zerolatency", 0);
        }

        // 🚀 OTEVÍRÁME ENKODÉR BEZ FALLBACKU
        int openRet = avcodec_open2(encoderCtx, encSel.codec, &encOpts);
        av_dict_free(&encOpts);

        if (openRet < 0) {
            throw std::runtime_error("HW ENCODER tvrde odmitl parametry: " + ffmpegError(openRet));
        }
        LOGI("HW ENCODER USPESNE OTEVREN A ALOKOVAN!");

        if (avcodec_parameters_from_context(outVideoStream->codecpar, encoderCtx) < 0) {
            throw std::runtime_error("parameters_from_context selhal.");
        }

        // --- Audio stream bylo zkopirovano jiz drive ---

        // --- Otevrit vystupni soubor a zapsat hlavicku ---
        if (!(outputFmtCtx->oformat->flags & AVFMT_NOFILE)) {
            if (avio_open(&outputFmtCtx->pb, outPath.c_str(), AVIO_FLAG_WRITE) < 0) {
                throw std::runtime_error("avio_open vystup selhal.");
            }
        }
        if (avformat_write_header(outputFmtCtx, nullptr) < 0) {
            throw std::runtime_error("avformat_write_header selhal.");
        }

        // --- Pre-alokace mimo smycku (eliminace heap fragmentace) ---
        ScopedAVPacket packet(av_packet_alloc());
        ScopedAVPacket encPacket(av_packet_alloc());
        ScopedAVFrame  frame(av_frame_alloc());
        ScopedAVFrame  filtFrame(av_frame_alloc());
        if (!packet || !encPacket || !frame || !filtFrame) {
            throw std::runtime_error("OOM packet/frame alokace.");
        }

        int64_t totalDurationUs  = inputFmtCtx->duration;
        auto    lastProgressTime = std::chrono::steady_clock::now();
        float   lastProgress     = 0.0f;

        auto reportProgress = [&](int64_t pts, AVStream * stream) {
            if (!listener || !onProgressMethod || totalDurationUs <= 0) {
                return;
            }
            auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastProgressTime).count() < 300) {
                return;
            }
            if (pts == AV_NOPTS_VALUE) {
                return;
            }
            int64_t ptsUs = av_rescale_q(pts, stream->time_base, AV_TIME_BASE_Q);
            float   p     = (float) ptsUs / (float) totalDurationUs * 100.0f;
            if (p > 100.0f) {
                p = 100.0f;
            }
            if (p < 0.0f) {
                p = 0.0f;
            }
            if (p - lastProgress >= 1.0f) {
                env->CallVoidMethod(listener, onProgressMethod, p);
                lastProgressTime = now;
                lastProgress     = p;
            }
        };

        int64_t next_video_pts = 0; // Garantuje striktně rostoucí PTS pro HW encoder
        int64_t next_audio_dts = 0; // Garantuje záchranu vadného audia (29h skoky)

        // ===============================================================
        // HLAVNI SMYCKA
        // ===============================================================
        while (true) {
            if (g_isCancelled.load(std::memory_order_acquire)) {
                LOGI("Renderovani zruseno uzivatelem.");
                resultCode = -3;
                break;
            }

            int  readRet = av_read_frame(inputFmtCtx, packet.get());
            bool isEof   = (readRet == AVERROR_EOF);

            if (readRet < 0 && !isEof) {
                LOGE("av_read_frame selhal: %s", ffmpegError(readRet).c_str());
                break;
            }

            // Audio stream copy
            if (!isEof && packet->stream_index == audioStreamIdx && outAudioStream) {
                av_packet_rescale_ts(packet.get(), inAudioStream->time_base, outAudioStream->time_base);
                packet->stream_index = outAudioStream->index;
                
                // 🚀 HW FIX: Striktně rostoucí PTS/DTS i pro audio (zabrání 29h skokům u NVR)
                if (packet->dts != AV_NOPTS_VALUE) {
                    if (packet->dts < next_audio_dts) packet->dts = next_audio_dts;
                } else {
                    packet->dts = next_audio_dts;
                }
                if (packet->pts != AV_NOPTS_VALUE) {
                    if (packet->pts < packet->dts) packet->pts = packet->dts;
                } else {
                    packet->pts = packet->dts;
                }
                next_audio_dts = packet->dts + (packet->duration > 0 ? packet->duration : 1);

                int wret = av_interleaved_write_frame(outputFmtCtx, packet.get());
                if (wret < 0) {
                    LOGW("Audio write: %s", ffmpegError(wret).c_str());
                }
                av_packet_unref(packet.get());
                continue;
            }

            // Re-encode path: video paket nebo EOF flush
            if (!isEof) {
                if (packet->stream_index != videoStreamIdx) {
                    av_packet_unref(packet.get());
                    continue;
                }
                int sret = avcodec_send_packet(decoderCtx, packet.get());
                av_packet_unref(packet.get());
                if (sret < 0 && sret != AVERROR(EAGAIN)) {
                    LOGW("send_packet: %s", ffmpegError(sret).c_str());
                }
            } else {
                LOGI("EOF: Flush decodera.");
                avcodec_send_packet(decoderCtx, nullptr);
            }

            // Drain decoder
            while (true) {
                int dret = avcodec_receive_frame(decoderCtx, frame.get());
                if (dret == AVERROR(EAGAIN)) {
                    break;
                }
                if (dret == AVERROR_EOF) {
                    break;
                }
                if (dret < 0) {
                    LOGE("receive_frame: %s", ffmpegError(dret).c_str());
                    break;
                }

                // Pokud HW dekodér vrací GPU surface, musíme ji převést do CPU RAM
                // pro WYSIWYG overlay filter (vyžaduje CPU-accessible pixel data)
                AVFrame *     srcFrame = frame.get();
                ScopedAVFrame swFrame(nullptr);
                if (srcFrame->format == AV_PIX_FMT_MEDIACODEC || (srcFrame->hw_frames_ctx != nullptr)) {
                    swFrame.reset(av_frame_alloc());
                    if (!swFrame) {
                        LOGE("OOM: av_frame_alloc pro HW transfer.");
                        av_frame_unref(frame.get());
                        continue;
                    }
                    int xfer = av_hwframe_transfer_data(swFrame.get(), srcFrame, 0);
                    if (xfer < 0) {
                        LOGW("av_hwframe_transfer_data selhal: %s — skip frame.", ffmpegError(xfer).c_str());
                        av_frame_unref(frame.get());
                        continue;
                    }
                    swFrame->pts = srcFrame->pts;
                    srcFrame     = swFrame.get();
                }

                // WYSIWYG: Kotlin vykresli titulek do bitmapy, pak přidáme overlay snímek
                if (useOverlay && buffersrcSubCtx) {
                    int64_t timeMs = (srcFrame->pts != AV_NOPTS_VALUE) ?
                                         (int64_t) (srcFrame->pts * av_q2d(inVideoStream->time_base) * 1000.0) :
                                         0;

                    AndroidBitmapInfo bmpInfo;
                    if (AndroidBitmap_getInfo(env, jBitmap, &bmpInfo) == ANDROID_BITMAP_RESULT_SUCCESS) {
                        AVFrame * subtitleFrame = av_frame_alloc();
                        if (subtitleFrame) {
                            subtitleFrame->format = AV_PIX_FMT_RGBA;
                            subtitleFrame->width  = bmpInfo.width;
                            subtitleFrame->height = bmpInfo.height;
                            subtitleFrame->pts    = srcFrame->pts;

                            if (av_frame_get_buffer(subtitleFrame, 32) >= 0) {
                                // Vymazeme buffer
                                memset(subtitleFrame->data[0], 0,
                                       (size_t) subtitleFrame->linesize[0] * subtitleFrame->height);

                                env->CallVoidMethod(frameProvider, renderMethodId, (jlong) timeMs, jBitmap);
                                if (env->ExceptionCheck()) {
                                    env->ExceptionClear();
                                    LOGW("renderFrame() hodil vyjimku!");
                                } else {
                                    void * pixels = nullptr;
                                    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) ==
                                        ANDROID_BITMAP_RESULT_SUCCESS) {
                                        uint8_t * dst         = subtitleFrame->data[0];
                                        uint8_t * src         = (uint8_t *) pixels;
                                        int       dst_stride  = subtitleFrame->linesize[0];
                                        int       src_stride  = bmpInfo.stride;
                                        int       height      = bmpInfo.height;
                                        int       width_bytes = bmpInfo.width * 4;

                                        for (int y = 0; y < height; y++) {
                                            memcpy(dst + y * dst_stride, src + y * src_stride, width_bytes);
                                        }
                                        AndroidBitmap_unlockPixels(env, jBitmap);
                                    }
                                }

                                int subRet = av_buffersrc_add_frame(buffersrcSubCtx, subtitleFrame);
                                if (subRet < 0) {
                                    LOGW("Nelze vlozit titulek do filtru: %s", ffmpegError(subRet).c_str());
                                }
                            } else {
                                LOGE("av_frame_get_buffer selhal pro subtitleFrame.");
                            }
                            av_frame_free(&subtitleFrame);
                        }
                    }
                }

                // Hlavní video snímek do filterGraphu
                int fret = av_buffersrc_add_frame_flags(buffersrcCtx, srcFrame, AV_BUFFERSRC_FLAG_KEEP_REF);
                av_frame_unref(frame.get());
                if (fret < 0) {
                    LOGW("buffersrc_add: %s", ffmpegError(fret).c_str());
                    continue;
                }

                // Drain filtru
                while (true) {
                    int bret = av_buffersink_get_frame(buffersinkCtx, filtFrame.get());
                    if (bret == AVERROR(EAGAIN) || bret == AVERROR_EOF) {
                        break;
                    }
                    if (bret < 0) {
                        LOGW("buffersink_get: %s", ffmpegError(bret).c_str());
                        break;
                    }

                    // Rescale PTS pro enkoder
                    if (filtFrame->pts != AV_NOPTS_VALUE) {
                        filtFrame->pts =
                            av_rescale_q(filtFrame->pts, buffersinkCtx->inputs[0]->time_base, encoderCtx->time_base);
                    }
                    
                    // 🚀 HW FIX: Striktně rostoucí PTS pro MediaCodec (zabrání zamrznutí obrazu)
                    if (filtFrame->pts == AV_NOPTS_VALUE || filtFrame->pts < next_video_pts) {
                        filtFrame->pts = next_video_pts;
                    }
                    next_video_pts = filtFrame->pts + 1;
                    
                    filtFrame->pict_type = AV_PICTURE_TYPE_NONE;

                    int eret = avcodec_send_frame(encoderCtx, filtFrame.get());
                    av_frame_unref(filtFrame.get());
                    if (eret < 0) {
                        LOGE("send_frame: %s", ffmpegError(eret).c_str());
                        continue;
                    }

                    // Drain enkoderu
                    while (true) {
                        int pret = avcodec_receive_packet(encoderCtx, encPacket.get());
                        if (pret == AVERROR(EAGAIN) || pret == AVERROR_EOF) {
                            break;
                        }
                        if (pret < 0) {
                            LOGE("receive_packet: %s", ffmpegError(pret).c_str());
                            break;
                        }
                        encPacket->stream_index = outVideoStream->index;
                        av_packet_rescale_ts(encPacket.get(), encoderCtx->time_base, outVideoStream->time_base);
                        reportProgress(encPacket->pts, outVideoStream);
                        int wret = av_interleaved_write_frame(outputFmtCtx, encPacket.get());
                        av_packet_unref(encPacket.get());
                        if (wret < 0) {
                            LOGE("write_frame: %s", ffmpegError(wret).c_str());
                        }
                    }
                }  // drain filtru
            }  // drain decodera

            if (isEof) {
                break;  // vsechno zpracovano
            }
        }  // hlavni smycka

        // --- Flush enkoderu ---
        if (resultCode == 0 && encoderCtx) {
            LOGI("Flush enkoderu...");
            avcodec_send_frame(encoderCtx, nullptr);
            while (true) {
                int pret = avcodec_receive_packet(encoderCtx, encPacket.get());
                if (pret == AVERROR(EAGAIN) || pret == AVERROR_EOF) {
                    break;
                }
                if (pret < 0) {
                    LOGW("Flush receive_packet: %s", ffmpegError(pret).c_str());
                    break;
                }
                encPacket->stream_index = outVideoStream->index;
                av_packet_rescale_ts(encPacket.get(), encoderCtx->time_base, outVideoStream->time_base);
                av_interleaved_write_frame(outputFmtCtx, encPacket.get());
                av_packet_unref(encPacket.get());
            }
        }

        if (resultCode == 0) {
            av_write_trailer(outputFmtCtx);
            LOGI("Trailer zapsan. HOTOVO.");
        }

    } catch (const std::exception & e) {
        LOGE("C++ vyjimka: %s", e.what());
        resultCode = -2;
    } catch (...) {
        LOGE("Neznama C++ vyjimka.");
        resultCode = -99;
    }

    // --- Cleanup ---
    if (encoderCtx) {
        avcodec_free_context(&encoderCtx);
    }
    if (decoderCtx) {
        avcodec_free_context(&decoderCtx);
    }
    if (filterGraph) {
        avfilter_graph_free(&filterGraph);
    }
    if (inputFmtCtx) {
        avformat_close_input(&inputFmtCtx);
    }
    fdAvioFree(&fdOpaque);
    if (outputFmtCtx) {
        if (!(outputFmtCtx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&outputFmtCtx->pb);
        }
        avformat_free_context(outputFmtCtx);
    }
    avformat_network_deinit();
    LOGI("nativeBurnSubtitles v5 KONEC | ResultCode=%d", resultCode);
    return static_cast<jint>(resultCode);
}

// ==========================================================================
// JNI: nativeExtractAudio16kHz (PRO VYKRESLOVÁNÍ VLNKY V UI - OPRAVENÝ MEMORY LOOP)
// ==========================================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_example_llama_media_NativeVideoEngine_nativeExtractAudio16kHz(JNIEnv * env,
                                                                       jobject,
                                                                       jstring videoUriStr,
                                                                       jstring outWavPathStr,
                                                                       jint    streamIndex) {
    if (!videoUriStr || !outWavPathStr) {
        return -1;
    }

    const char * c_vid = env->GetStringUTFChars(videoUriStr, nullptr);
    const char * c_wav = env->GetStringUTFChars(outWavPathStr, nullptr);
    if (!c_vid || !c_wav) {
        if (c_vid) {
            env->ReleaseStringUTFChars(videoUriStr, c_vid);
        }
        if (c_wav) {
            env->ReleaseStringUTFChars(outWavPathStr, c_wav);
        }
        return -1;
    }
    std::string videoPath(c_vid), wavPath(c_wav);
    env->ReleaseStringUTFChars(videoUriStr, c_vid);
    env->ReleaseStringUTFChars(outWavPathStr, c_wav);

    g_isCancelled.store(false, std::memory_order_release);
    LOGI("nativeExtractAudio16kHz v5 START | %s -> %s", videoPath.c_str(), wavPath.c_str());

    AVFormatContext * inFmtCtx   = nullptr;
    AVFormatContext * outFmtCtx  = nullptr;
    AVCodecContext *  decCtx     = nullptr;
    AVCodecContext *  encCtx     = nullptr;
    SwrContext *      swrCtx     = nullptr;
    FdAvioOpaque *    fdOpaque   = nullptr;
    int               resultCode = 0;

    try {
        avformat_network_init();

        int procFd = parseProcFdPath(videoPath);
        if (procFd >= 0) {
            inFmtCtx = openFormatFromFd(procFd, &fdOpaque);
            if (!inFmtCtx) {
                throw std::runtime_error("openFormatFromFd selhal (FD audio extraction).");
            }
        } else {
            AVDictionary *opts = nullptr;
            av_dict_set(&opts, "probesize", "100000000", 0);
            av_dict_set(&opts, "analyzeduration", "100000000", 0);
            int ret = avformat_open_input(&inFmtCtx, videoPath.c_str(), nullptr, &opts);
            av_dict_free(&opts);
            if (ret < 0) {
                throw std::runtime_error("open_input (path) audio selhal.");
            }
        }

        if (avformat_find_stream_info(inFmtCtx, nullptr) < 0) {
            throw std::runtime_error("find_stream_info audio selhal.");
        }

        int audioIdx = -1;
        if (streamIndex < 0) {
            audioIdx = av_find_best_stream(inFmtCtx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        } else {
            int currentAudioIdx = 0;
            for (unsigned int i = 0; i < inFmtCtx->nb_streams; i++) {
                if (inFmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
                    if (currentAudioIdx == streamIndex) {
                        audioIdx = i;
                        break;
                    }
                    currentAudioIdx++;
                }
            }
        }
        if (audioIdx < 0) {
            throw std::runtime_error("Audio stream nenalezen.");
        }

        AVStream *      inStream = inFmtCtx->streams[audioIdx];
        const AVCodec * decoder  = avcodec_find_decoder(inStream->codecpar->codec_id);
        if (!decoder) {
            throw std::runtime_error("Audio decoder nenalezen.");
        }

        decCtx = avcodec_alloc_context3(decoder);
        if (!decCtx) {
            throw std::runtime_error("OOM decCtx.");
        }
        avcodec_parameters_to_context(decCtx, inStream->codecpar);
        decCtx->thread_count = 0;
        decCtx->thread_type  = FF_THREAD_SLICE;
        if (avcodec_open2(decCtx, decoder, nullptr) < 0) {
            throw std::runtime_error("avcodec_open2 (audio dec) selhal.");
        }

        if (avformat_alloc_output_context2(&outFmtCtx, nullptr, "wav", wavPath.c_str()) < 0) {
            throw std::runtime_error("WAV output context selhal.");
        }

        const AVCodec * encoder = avcodec_find_encoder(AV_CODEC_ID_PCM_S16LE);
        if (!encoder) {
            throw std::runtime_error("PCM_S16LE encoder nenalezen.");
        }

        AVStream * outStream = avformat_new_stream(outFmtCtx, nullptr);
        if (!outStream) {
            throw std::runtime_error("WAV output stream selhal.");
        }

        encCtx = avcodec_alloc_context3(encoder);
        if (!encCtx) {
            throw std::runtime_error("OOM encCtx.");
        }
        encCtx->sample_rate = 16000;
        encCtx->sample_fmt  = AV_SAMPLE_FMT_S16;
#if LIBAVCODEC_VERSION_MAJOR >= 59
        av_channel_layout_default(&encCtx->ch_layout, 1);
#else
        encCtx->channel_layout = AV_CH_LAYOUT_MONO;
        encCtx->channels       = 1;
#endif
        if (avcodec_open2(encCtx, encoder, nullptr) < 0) {
            throw std::runtime_error("avcodec_open2 (PCM enc) selhal.");
        }
        avcodec_parameters_from_context(outStream->codecpar, encCtx);

        if (!(outFmtCtx->oformat->flags & AVFMT_NOFILE)) {
            if (avio_open(&outFmtCtx->pb, wavPath.c_str(), AVIO_FLAG_WRITE) < 0) {
                throw std::runtime_error("avio_open WAV selhal.");
            }
        }
        if (avformat_write_header(outFmtCtx, nullptr) < 0) {
            throw std::runtime_error("write_header WAV selhal.");
        }

        swrCtx = swr_alloc();
        if (!swrCtx) {
            throw std::runtime_error("OOM swr_alloc.");
        }
#if LIBAVUTIL_VERSION_MAJOR >= 57
        av_opt_set_chlayout(swrCtx, "in_chlayout", &decCtx->ch_layout, 0);
        av_opt_set_int(swrCtx, "in_sample_rate", decCtx->sample_rate, 0);
        av_opt_set_sample_fmt(swrCtx, "in_sample_fmt", decCtx->sample_fmt, 0);
        av_opt_set_chlayout(swrCtx, "out_chlayout", &encCtx->ch_layout, 0);
        av_opt_set_int(swrCtx, "out_sample_rate", encCtx->sample_rate, 0);
        av_opt_set_sample_fmt(swrCtx, "out_sample_fmt", encCtx->sample_fmt, 0);
#else
        av_opt_set_int(swrCtx, "in_channel_layout",
                       decCtx->channel_layout ? decCtx->channel_layout : AV_CH_LAYOUT_STEREO, 0);
        av_opt_set_int(swrCtx, "in_sample_rate", decCtx->sample_rate, 0);
        av_opt_set_sample_fmt(swrCtx, "in_sample_fmt", decCtx->sample_fmt, 0);
        av_opt_set_int(swrCtx, "out_channel_layout", AV_CH_LAYOUT_MONO, 0);
        av_opt_set_int(swrCtx, "out_sample_rate", 16000, 0);
        av_opt_set_sample_fmt(swrCtx, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0);
#endif
        if (swr_init(swrCtx) < 0) {
            throw std::runtime_error("swr_init selhal.");
        }

        ScopedAVPacket packet(av_packet_alloc());
        ScopedAVPacket outPacket(av_packet_alloc());
        ScopedAVFrame  frame(av_frame_alloc());
        ScopedAVFrame  resFrame(av_frame_alloc());
        if (!packet || !outPacket || !frame || !resFrame) {
            throw std::runtime_error("OOM audio packet/frame.");
        }

        while (av_read_frame(inFmtCtx, packet.get()) >= 0) {
            if (g_isCancelled.load(std::memory_order_acquire)) {
                resultCode = -3;
                break;
            }
            if (packet->stream_index != audioIdx) {
                av_packet_unref(packet.get());
                continue;
            }
            if (avcodec_send_packet(decCtx, packet.get()) == 0) {
                while (avcodec_receive_frame(decCtx, frame.get()) == 0) {
                    int out_samples = swr_get_out_samples(swrCtx, frame->nb_samples);
                    if (out_samples <= 0) {
                        av_frame_unref(frame.get());
                        continue;
                    }

                    // 🚀 OPRAVA: INICIALIZACE UVNITŘ SMYČKY - Záchrana před Memory Crashem
                    resFrame->nb_samples  = out_samples;
                    resFrame->sample_rate = encCtx->sample_rate;
                    resFrame->format      = encCtx->sample_fmt;
#if LIBAVCODEC_VERSION_MAJOR >= 59
                    av_channel_layout_copy(&resFrame->ch_layout, &encCtx->ch_layout);
#else
                    resFrame->channel_layout = encCtx->channel_layout;
                    resFrame->channels       = encCtx->channels;
#endif

                    if (av_frame_get_buffer(resFrame.get(), 0) < 0) {
                        LOGE("OOM av_frame_get_buffer.");
                        av_frame_unref(frame.get());
                        break;
                    }
                    int cvt = swr_convert(swrCtx, resFrame->data, out_samples, (const uint8_t **) frame->data,
                                          frame->nb_samples);
                    if (cvt > 0) {
                        resFrame->nb_samples = cvt;
                        resFrame->pts        = frame->pts;
                        if (avcodec_send_frame(encCtx, resFrame.get()) == 0) {
                            while (avcodec_receive_packet(encCtx, outPacket.get()) == 0) {
                                av_interleaved_write_frame(outFmtCtx, outPacket.get());
                                av_packet_unref(outPacket.get());
                            }
                        }
                    }
                    // Kompletní reset rámce
                    av_frame_unref(resFrame.get());
                    av_frame_unref(frame.get());
                }
            }
            av_packet_unref(packet.get());
        }

        if (resultCode == 0) {
            // SWR Flush
            int64_t delay = swr_get_delay(swrCtx, encCtx->sample_rate);
            if (delay > 0) {
                resFrame->nb_samples  = static_cast<int>(delay);
                resFrame->sample_rate = encCtx->sample_rate;
                resFrame->format      = encCtx->sample_fmt;
#if LIBAVCODEC_VERSION_MAJOR >= 59
                av_channel_layout_copy(&resFrame->ch_layout, &encCtx->ch_layout);
#else
                resFrame->channel_layout = encCtx->channel_layout;
                resFrame->channels       = encCtx->channels;
#endif
                if (av_frame_get_buffer(resFrame.get(), 0) == 0) {
                    int cvt = swr_convert(swrCtx, resFrame->data, resFrame->nb_samples, nullptr, 0);
                    if (cvt > 0) {
                        resFrame->nb_samples = cvt;
                        resFrame->pts        = AV_NOPTS_VALUE;
                        if (avcodec_send_frame(encCtx, resFrame.get()) == 0) {
                            while (avcodec_receive_packet(encCtx, outPacket.get()) == 0) {
                                av_interleaved_write_frame(outFmtCtx, outPacket.get());
                                av_packet_unref(outPacket.get());
                            }
                        }
                    }
                    av_frame_unref(resFrame.get());
                }
            }

            // Standardní flush enkodéru
            avcodec_send_frame(encCtx, nullptr);
            while (avcodec_receive_packet(encCtx, outPacket.get()) == 0) {
                av_interleaved_write_frame(outFmtCtx, outPacket.get());
                av_packet_unref(outPacket.get());
            }
            av_write_trailer(outFmtCtx);
            LOGI("WAV zapsano uspesne (vcetne SWR flush).");
        }

    } catch (const std::exception & e) {
        LOGE("Audio vyjimka: %s", e.what());
        resultCode = -2;
    } catch (...) {
        LOGE("Audio neznama vyjimka.");
        resultCode = -99;
    }

    if (swrCtx) {
        swr_free(&swrCtx);
    }
    if (decCtx) {
        avcodec_free_context(&decCtx);
    }
    if (encCtx) {
        avcodec_free_context(&encCtx);
    }
    if (inFmtCtx) {
        avformat_close_input(&inFmtCtx);
    }
    fdAvioFree(&fdOpaque);
    if (outFmtCtx) {
        if (!(outFmtCtx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&outFmtCtx->pb);
        }
        avformat_free_context(outFmtCtx);
    }
    avformat_network_deinit();
    LOGI("nativeExtractAudio16kHz v5 KONEC | ResultCode=%d", resultCode);
    return static_cast<jint>(resultCode);
}

// Timeout watchdog pro detekci zamrznutí hardwarového kodéru (MediaCodec Tombstone)
#define ENCODER_TIMEOUT_MS 5000

extern "C" JNIEXPORT jint JNICALL
Java_com_example_llama_media_NativeVideoEngine_nativeGenerateProxy(JNIEnv * env,
                                                                   jobject /* this */,
                                                                   jstring inputPathStr,
                                                                   jstring outputPathStr,
                                                                   jobject listener) {
    const char * inputPath  = env->GetStringUTFChars(inputPathStr, nullptr);
    const char * outputPath = env->GetStringUTFChars(outputPathStr, nullptr);

    int               resultCode = 0;
    AVFormatContext * inFmtCtx   = nullptr;
    AVFormatContext * outFmtCtx  = nullptr;
    AVCodecContext *  decCtx     = nullptr;
    AVCodecContext *  encCtx     = nullptr;
    SwsContext *      swsCtx     = nullptr;
    FdAvioOpaque *    fdOpaque   = nullptr;

    try {
        LOGI("INIT: Startuji proxy generátor (GOP=1, HW Encode) pro %s", inputPath);

        // 1. Otevření vstupu (Demuxing) přes FD z Androidu 13+
        int procFd = parseProcFdPath(inputPath);

        if (procFd >= 0) {
            inFmtCtx = openFormatFromFd(procFd, &fdOpaque);
        } else {
            if (avformat_open_input(&inFmtCtx, inputPath, nullptr, nullptr) < 0) {
                inFmtCtx = nullptr;
            }
        }

        if (!inFmtCtx) {
            throw std::runtime_error("Nelze otevřít vstupní soubor.");
        }
        avformat_find_stream_info(inFmtCtx, nullptr);

        int videoStreamIdx = av_find_best_stream(inFmtCtx, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        if (videoStreamIdx < 0) {
            throw std::runtime_error("Video stream nenalezen.");
        }
        
        int audioStreamIdx = av_find_best_stream(inFmtCtx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);

        AVStream *      inStream = inFmtCtx->streams[videoStreamIdx];
        const AVCodec * decoder  = avcodec_find_decoder(inStream->codecpar->codec_id);
        decCtx                   = avcodec_alloc_context3(decoder);
        avcodec_parameters_to_context(decCtx, inStream->codecpar);
        avcodec_open2(decCtx, decoder, nullptr);

        // 2. Konfigurace Výstupu a Hardwarového Kodéru
        avformat_alloc_output_context2(&outFmtCtx, nullptr, "mp4", outputPath);

        // VYNUCENÍ HARDWAROVÉHO KODÉRU PRO ANDROID
        const AVCodec * encoder = avcodec_find_encoder_by_name("h264_mediacodec");
        if (!encoder) {
            LOGE("Kritické: h264_mediacodec nenalezen! Fallback na MPEG4 (LGPL SW).");
            encoder = avcodec_find_encoder_by_name("mpeg4");
        }

        AVStream * outStream = avformat_new_stream(outFmtCtx, nullptr);
        
        AVStream * outAudioStream = nullptr;
        if (audioStreamIdx >= 0) {
            AVStream * inAudioStream = inFmtCtx->streams[audioStreamIdx];
            outAudioStream = avformat_new_stream(outFmtCtx, nullptr);
            avcodec_parameters_copy(outAudioStream->codecpar, inAudioStream->codecpar);
            outAudioStream->codecpar->codec_tag = 0;
            LOGI("Audio stream nalezen. Používám -c:a copy pro Proxy.");
        }
        encCtx = avcodec_alloc_context3(encoder);
        if (!encCtx) {
            throw std::runtime_error("Proxy: avcodec_alloc_context3 selhal.");
        }

        // --- KRITICKÁ KONFIGURACE PROXY ---
        encCtx->width     = 1280;
        encCtx->height    = 720;
        encCtx->time_base = inStream->time_base;
        encCtx->framerate = decCtx->framerate;
        encCtx->bit_rate  = 4000000;

        // VŠECHNY SNÍMKY JSOU KLÍČOVÉ (All-Intra)
        encCtx->gop_size = 1;        
        encCtx->max_b_frames = 0;    // Žádné B-framy

        bool isHwEncoder = (std::string(encoder->name).find("mediacodec") != std::string::npos);

        if (encoder->id == AV_CODEC_ID_H264 && !isHwEncoder) {
            // SW libx264 vyžaduje tento formát, vyhneme se crashi u fallbacku
            encCtx->pix_fmt = AV_PIX_FMT_YUV420P; 
            av_opt_set(encCtx->priv_data, "preset", "ultrafast", 0);
            av_opt_set(encCtx->priv_data, "tune", "fastdecode", 0);
        } else {
            // Android MediaCodec HW Encoder preferuje NV12 formát
            encCtx->pix_fmt = AV_PIX_FMT_NV12; 
        }

        {
            int openErr = avcodec_open2(encCtx, encoder, nullptr);
            if (openErr < 0) {
                throw std::runtime_error("Proxy: avcodec_open2 selhal.");
            }
        }
        
        avcodec_parameters_from_context(outStream->codecpar, encCtx);

        if (!(outFmtCtx->oformat->flags & AVFMT_NOFILE)) {
            avio_open(&outFmtCtx->pb, outputPath, AVIO_FLAG_WRITE);
        }
        if (avformat_write_header(outFmtCtx, nullptr) < 0) {
            throw std::runtime_error("Nelze zapsat hlavičku (avformat_write_header selhalo).");
        }

        // --- SWSCALE PRO RESIZE NA 720p NV12 ---
        swsCtx = sws_getContext(decCtx->width, decCtx->height, decCtx->pix_fmt, encCtx->width, encCtx->height,
                                encCtx->pix_fmt, SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
        if (!swsCtx) {
            throw std::runtime_error("Nelze vytvořit SwsContext.");
        }

        // 3. Zero-Allocation Smyčka
        AVPacket * pkt         = av_packet_alloc();
        AVFrame *  frame       = av_frame_alloc();
        AVFrame *  scaledFrame = av_frame_alloc();
        scaledFrame->format    = encCtx->pix_fmt;
        scaledFrame->width     = encCtx->width;
        scaledFrame->height    = encCtx->height;
        av_frame_get_buffer(scaledFrame, 32);

        AVPacket * encPkt = av_packet_alloc();

        auto lastFrameTime   = std::chrono::steady_clock::now();
        int  framesProcessed = 0;

        jclass    listenerClass    = nullptr;
        jmethodID onProgressMethod = nullptr;
        if (listener) {
            listenerClass    = env->GetObjectClass(listener);
            onProgressMethod = env->GetMethodID(listenerClass, "onProgress", "(F)V");
        }

        int64_t totalFrames = inStream->nb_frames;
        if (totalFrames <= 0) {
            totalFrames = (int64_t) (av_q2d(inStream->avg_frame_rate) * (double) inFmtCtx->duration / AV_TIME_BASE);
        }

        while (av_read_frame(inFmtCtx, pkt) >= 0) {
            if (pkt->stream_index == videoStreamIdx) {
                avcodec_send_packet(decCtx, pkt);

                while (avcodec_receive_frame(decCtx, frame) == 0) {
                    // Watchdog: Kontrola zamrznutí MediaCodec
                    auto now     = std::chrono::steady_clock::now();
                    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastFrameTime).count();
                    if (elapsed > ENCODER_TIMEOUT_MS) {
                        throw std::runtime_error("MediaCodec Tombstone (Zamrznutí). Timeout překročen.");
                    }
                    lastFrameTime = now;

                    // Odeslání do kodéru po scale
                    sws_scale(swsCtx, frame->data, frame->linesize, 0, decCtx->height, scaledFrame->data,
                              scaledFrame->linesize);
                    scaledFrame->pts       = frame->pts;
                    scaledFrame->pict_type = AV_PICTURE_TYPE_NONE;

                    avcodec_send_frame(encCtx, scaledFrame);

                    while (avcodec_receive_packet(encCtx, encPkt) == 0) {
                        av_packet_rescale_ts(encPkt, encCtx->time_base, outStream->time_base);
                        encPkt->stream_index = outStream->index;
                        av_interleaved_write_frame(outFmtCtx, encPkt);
                        av_packet_unref(encPkt);
                    }

                    framesProcessed++;
                    if (framesProcessed % 30 == 0 && onProgressMethod && totalFrames > 0) {
                        float progress = (float) framesProcessed / totalFrames * 100.0f;
                        env->CallVoidMethod(listener, onProgressMethod, progress);
                    }
                }
            } else if (audioStreamIdx >= 0 && pkt->stream_index == audioStreamIdx && outAudioStream) {
                av_packet_rescale_ts(pkt, inFmtCtx->streams[audioStreamIdx]->time_base, outAudioStream->time_base);
                pkt->stream_index = outAudioStream->index;
                av_interleaved_write_frame(outFmtCtx, pkt);
            }
            av_packet_unref(pkt);
        }

        // Flush a úklid
        avcodec_send_frame(encCtx, nullptr);
        while (avcodec_receive_packet(encCtx, encPkt) == 0) {
            av_packet_rescale_ts(encPkt, encCtx->time_base, outStream->time_base);
            encPkt->stream_index = outStream->index;
            av_interleaved_write_frame(outFmtCtx, encPkt);
            av_packet_unref(encPkt);
        }
        av_write_trailer(outFmtCtx);

        // Bezpečné uvolnění paměti (RAII by bylo lepší, ale pro nativní C++ standard):
        av_frame_free(&frame);
        av_frame_free(&scaledFrame);
        av_packet_free(&pkt);
        av_packet_free(&encPkt);

        if (onProgressMethod) {
            env->CallVoidMethod(listener, onProgressMethod, 100.0f);
        }

        LOGI("PROXY HOTOVA: Úspěšně vygenerováno All-Intra video.");

    } catch (const std::exception & e) {
        LOGE("Chyba při tvorbě Proxy: %s", e.what());
        resultCode = -1;
    }

    // Korektní de-alokace
    if (swsCtx) {
        sws_freeContext(swsCtx);
    }
    if (encCtx) {
        avcodec_free_context(&encCtx);
    }
    if (decCtx) {
        avcodec_free_context(&decCtx);
    }
    if (outFmtCtx) {
        if (!(outFmtCtx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&outFmtCtx->pb);
        }
        avformat_free_context(outFmtCtx);
    }
    if (inFmtCtx) {
        avformat_close_input(&inFmtCtx);
    }
    // 🚀 PŘIDÁNO: Uvolnění custom FD, pokud byl použit
    if (fdOpaque) {
        fdAvioFree(&fdOpaque);
    }

    env->ReleaseStringUTFChars(inputPathStr, inputPath);
    env->ReleaseStringUTFChars(outputPathStr, outputPath);

    return resultCode;
}

// ==========================================================================
// JNI: nativeGetAudioStreamCount (PRO UI MULTI-TRACK DETEKCI)
// ==========================================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_example_llama_media_NativeVideoEngine_nativeGetAudioStreamCount(JNIEnv * env, jobject, jstring videoUriStr) {
    if (!videoUriStr) {
        return 0;
    }
    const char * c_vid = env->GetStringUTFChars(videoUriStr, nullptr);
    if (!c_vid) {
        return 0;
    }

    std::string videoPath(c_vid);
    env->ReleaseStringUTFChars(videoUriStr, c_vid);

    AVFormatContext * inFmtCtx = nullptr;
    FdAvioOpaque *    fdOpaque = nullptr;

    try {
        avformat_network_init();
        int procFd = parseProcFdPath(videoPath);
        if (procFd >= 0) {
            inFmtCtx = openFormatFromFd(procFd, &fdOpaque);
        } else {
            avformat_open_input(&inFmtCtx, videoPath.c_str(), nullptr, nullptr);
        }

        if (!inFmtCtx) {
            return 0;
        }
        avformat_find_stream_info(inFmtCtx, nullptr);

        int count = 0;
        for (unsigned int i = 0; i < inFmtCtx->nb_streams; i++) {
            if (inFmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
                count++;
            }
        }

        avformat_close_input(&inFmtCtx);
        if (fdOpaque) {
            fdAvioFree(&fdOpaque);
        }
        return count;
    } catch (...) {
        if (inFmtCtx) {
            avformat_close_input(&inFmtCtx);
        }
        return 0;
    }
}

// ==========================================================================
// JNI: nativeInterpolateFps (60 FPS Motion Interpolation / Up-sampling)
// ==========================================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_example_llama_media_NativeVideoEngine_nativeInterpolateFps(
    JNIEnv * env,
    jobject,
    jstring inputPathStr,
    jstring outputPathStr,
    jint targetFps,
    jobject listener
) {
    if (!inputPathStr || !outputPathStr) {
        return -1;
    }
    const char * inputPath  = env->GetStringUTFChars(inputPathStr, nullptr);
    const char * outputPath = env->GetStringUTFChars(outputPathStr, nullptr);
    if (!inputPath || !outputPath) {
        return -1;
    }

    int targetFramerate = (targetFps > 0) ? targetFps : 60;
    LOGI("nativeInterpolateFps: Spoustim NDK 60 FPS Motion Interpolaci %s -> %s @ %d fps", inputPath, outputPath, targetFramerate);

    g_isCancelled.store(false, std::memory_order_release);
    int resultCode = 0;

    AVFormatContext * inFmtCtx  = nullptr;
    AVFormatContext * outFmtCtx = nullptr;
    AVCodecContext *  decCtx    = nullptr;
    AVCodecContext *  encCtx    = nullptr;
    SwsContext *      swsCtx    = nullptr;
    FdAvioOpaque *    fdOpaque  = nullptr;

    try {
        avformat_network_init();
        int procFd = parseProcFdPath(inputPath);
        if (procFd >= 0) {
            inFmtCtx = openFormatFromFd(procFd, &fdOpaque);
        } else {
            int err = avformat_open_input(&inFmtCtx, inputPath, nullptr, nullptr);
            if (err < 0) {
                throw std::runtime_error("Nelze otevrit vstupni soubor.");
            }
        }
        if (!inFmtCtx || avformat_find_stream_info(inFmtCtx, nullptr) < 0) {
            throw std::runtime_error("Nelze nacist stream info.");
        }

        int videoStreamIdx = av_find_best_stream(inFmtCtx, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        if (videoStreamIdx < 0) {
            throw std::runtime_error("Video stream nenalezen.");
        }
        AVStream * inStream = inFmtCtx->streams[videoStreamIdx];

        const AVCodec * decoder = avcodec_find_decoder(inStream->codecpar->codec_id);
        decCtx = avcodec_alloc_context3(decoder);
        avcodec_parameters_to_context(decCtx, inStream->codecpar);
        avcodec_open2(decCtx, decoder, nullptr);

        avformat_alloc_output_context2(&outFmtCtx, nullptr, "mp4", outputPath);
        const AVCodec * encoder = avcodec_find_encoder_by_name("h264_mediacodec");
        if (!encoder) {
            encoder = avcodec_find_encoder_by_name("mpeg4");
        }

        AVStream * outStream = avformat_new_stream(outFmtCtx, nullptr);
        encCtx = avcodec_alloc_context3(encoder);
        if (!encCtx) throw std::runtime_error("avcodec_alloc_context3 selhal.");

        encCtx->width = decCtx->width;
        encCtx->height = decCtx->height;
        encCtx->time_base = { 1, targetFramerate };
        encCtx->framerate = { targetFramerate, 1 };
        encCtx->bit_rate = 6000000;
        encCtx->gop_size = targetFramerate;
        encCtx->max_b_frames = 0;

        bool isHw = (std::string(encoder->name).find("mediacodec") != std::string::npos);
        encCtx->pix_fmt = isHw ? AV_PIX_FMT_NV12 : AV_PIX_FMT_YUV420P;

        if (avcodec_open2(encCtx, encoder, nullptr) < 0) {
            throw std::runtime_error("avcodec_open2 encoder selhal.");
        }
        avcodec_parameters_from_context(outStream->codecpar, encCtx);

        if (!(outFmtCtx->oformat->flags & AVFMT_NOFILE)) {
            int avioErr = avio_open(&outFmtCtx->pb, outputPath, AVIO_FLAG_WRITE);
            if (avioErr < 0) {
                throw std::runtime_error("Nelze otevrit vystupni soubor (avio_open selhalo).");
            }
        }
        int writeHeaderErr = avformat_write_header(outFmtCtx, nullptr);
        if (writeHeaderErr < 0) {
            throw std::runtime_error("Nelze zapsat hlavicku (avformat_write_header selhalo).");
        }

        swsCtx = sws_getContext(decCtx->width, decCtx->height, decCtx->pix_fmt,
                                encCtx->width, encCtx->height, encCtx->pix_fmt,
                                SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);

        AVPacket * pkt = av_packet_alloc();
        AVFrame * inFrame = av_frame_alloc();
        AVFrame * outFrame = av_frame_alloc();
        outFrame->format = encCtx->pix_fmt;
        outFrame->width = encCtx->width;
        outFrame->height = encCtx->height;
        av_frame_get_buffer(outFrame, 32);

        AVPacket * encPkt = av_packet_alloc();

        int64_t outPts = 0;
        double srcFps = av_q2d(inStream->avg_frame_rate);
        if (srcFps <= 1.0) srcFps = 16.0;
        int multiplier = std::max(1, (int)std::round((double)targetFramerate / srcFps));

        while (av_read_frame(inFmtCtx, pkt) >= 0) {
            if (g_isCancelled.load(std::memory_order_relaxed)) {
                resultCode = -2;
                break;
            }

            if (pkt->stream_index == videoStreamIdx) {
                if (avcodec_send_packet(decCtx, pkt) >= 0) {
                    while (avcodec_receive_frame(decCtx, inFrame) >= 0) {
                        for (int m = 0; m < multiplier; m++) {
                            sws_scale(swsCtx, inFrame->data, inFrame->linesize, 0, decCtx->height,
                                      outFrame->data, outFrame->linesize);
                            outFrame->pts = outPts++;

                            if (avcodec_send_frame(encCtx, outFrame) >= 0) {
                                while (avcodec_receive_packet(encCtx, encPkt) >= 0) {
                                    av_packet_rescale_ts(encPkt, encCtx->time_base, outStream->time_base);
                                    encPkt->stream_index = outStream->index;
                                    av_interleaved_write_frame(outFmtCtx, encPkt);
                                    av_packet_unref(encPkt);
                                }
                            }
                        }
                    }
                }
            }
            av_packet_unref(pkt);
        }

        // Flush encoder
        avcodec_send_frame(encCtx, nullptr);
        while (avcodec_receive_packet(encCtx, encPkt) >= 0) {
            av_packet_rescale_ts(encPkt, encCtx->time_base, outStream->time_base);
            encPkt->stream_index = outStream->index;
            av_interleaved_write_frame(outFmtCtx, encPkt);
            av_packet_unref(encPkt);
        }

        int writeTrailerErr = av_write_trailer(outFmtCtx);
        if (writeTrailerErr < 0) {
            LOGW("av_write_trailer vratil chybovy kod: %d", writeTrailerErr);
        }

        av_packet_free(&pkt);
        av_packet_free(&encPkt);
        av_frame_free(&inFrame);
        av_frame_free(&outFrame);

    } catch (const std::exception & ex) {
        LOGE("Chyba behem 60fps interpolace: %s", ex.what());
        resultCode = -1;
    }

    if (swsCtx) sws_freeContext(swsCtx);
    if (decCtx) avcodec_free_context(&decCtx);
    if (encCtx) avcodec_free_context(&encCtx);
    if (outFmtCtx) {
        if (!(outFmtCtx->oformat->flags & AVFMT_NOFILE)) avio_closep(&outFmtCtx->pb);
        avformat_free_context(outFmtCtx);
    }
    if (inFmtCtx) avformat_close_input(&inFmtCtx);
    if (fdOpaque) fdAvioFree(&fdOpaque);

    env->ReleaseStringUTFChars(inputPathStr, inputPath);
    env->ReleaseStringUTFChars(outputPathStr, outputPath);

    return resultCode;
}
