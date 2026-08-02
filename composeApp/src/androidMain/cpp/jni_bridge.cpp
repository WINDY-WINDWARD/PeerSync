#include <jni.h>
#include "audio_engine.h"
#include <android/log.h>
#include <pthread.h>

#define LOG_TAG "JniBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static AudioEngine* gAudioEngine = nullptr;
static JavaVM* gJavaVM = nullptr;
static jobject gAudioBridgeObj = nullptr;
static jmethodID gOnNativeAudioFrameMethod = nullptr;
static jmethodID gOnStreamErrorMethod = nullptr;

// Thread-exit hook for safely detaching JNI without impacting real-time audio path
static pthread_key_t gJniThreadKey;
static pthread_once_t gJniThreadKeyOnce = PTHREAD_ONCE_INIT;

static void detachJniThread(void* env) {
    if (gJavaVM) {
        gJavaVM->DetachCurrentThread();
    }
}

static void makeJniThreadKey() {
    pthread_key_create(&gJniThreadKey, detachJniThread);
}

// Attach the calling thread ONCE and stay attached. AAudio callback threads
// live for the stream lifetime; per-frame AttachCurrentThread/DetachCurrentThread
// creates/destroys a Java Thread object every 20ms on a real-time thread.
static JNIEnv* getAttachedEnv() {
    if (!gJavaVM) return nullptr;
    JNIEnv* env = nullptr;
    jint res = gJavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (gJavaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            // Register destructor to prevent ART crashes when the AAudio thread exits
            pthread_once(&gJniThreadKeyOnce, makeJniThreadKey);
            pthread_setspecific(gJniThreadKey, env);
        } else {
            return nullptr;
        }
    }
    return env;
}

static void onStreamErrorFromNative(const char* errorMessage) {
    if (!gAudioBridgeObj || !gOnStreamErrorMethod || errorMessage == nullptr) return;
    JNIEnv* env = getAttachedEnv();
    if (env) {
        jstring jmsg = env->NewStringUTF(errorMessage);
        env->CallVoidMethod(gAudioBridgeObj, gOnStreamErrorMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

static void onNativeAudioFrame(uint8_t flag, const uint8_t* encodedData, size_t size) {
    if (!gAudioBridgeObj || !gOnNativeAudioFrameMethod) return;
    JNIEnv* env = getAttachedEnv();
    if (env && encodedData && size > 0) {
        // Fresh array per frame: the Kotlin side emits it asynchronously into
        // a SharedFlow, so a reused buffer would corrupt in-flight frames.
        jbyteArray byteArray = env->NewByteArray(static_cast<jsize>(size));
        env->SetByteArrayRegion(byteArray, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(encodedData));
        env->CallVoidMethod(gAudioBridgeObj, gOnNativeAudioFrameMethod, static_cast<jbyte>(flag), byteArray);
        env->DeleteLocalRef(byteArray);
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeInit(JNIEnv* env, jobject thiz) {
    env->GetJavaVM(&gJavaVM);
    if (gAudioBridgeObj) {
        env->DeleteGlobalRef(gAudioBridgeObj);
    }
    gAudioBridgeObj = env->NewGlobalRef(thiz);

    jclass clazz = env->GetObjectClass(thiz);
    gOnNativeAudioFrameMethod = env->GetMethodID(clazz, "onNativeAudioFrame", "(B[B)V");
    gOnStreamErrorMethod = env->GetMethodID(clazz, "onStreamError", "(Ljava/lang/String;)V");

    if (!gAudioEngine) {
        gAudioEngine = new AudioEngine();
        gAudioEngine->setFrameCallback(onNativeAudioFrame);
        gAudioEngine->setStreamErrorCallback(onStreamErrorFromNative);
    }

    LOGI("AudioBridge JNI initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeStartAudio(JNIEnv* env, jobject thiz, jint sessionId) {
    if (gAudioEngine) {
        return gAudioEngine->start(sessionId) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeStopAudio(JNIEnv* env, jobject thiz) {
    if (gAudioEngine) {
        gAudioEngine->stop();
    }
}

JNIEXPORT jint JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeGetLocalMusicFreeSpace(JNIEnv* env, jobject thiz) {
    if (gAudioEngine) {
        return static_cast<jint>(gAudioEngine->getLocalMusicFreeSpace());
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeClearLocalMusicBuffers(JNIEnv* env, jobject thiz) {
    if (gAudioEngine) {
        gAudioEngine->clearLocalMusicBuffers();
    }
}

JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeFeedLocalMusic(JNIEnv* env, jobject thiz, jbyteArray pcmData) {
    if (gAudioEngine && pcmData) {
        jsize len = env->GetArrayLength(pcmData);
        if (len > 0) {
            jbyte* elements = env->GetByteArrayElements(pcmData, nullptr);
            gAudioEngine->feedLocalMusic(reinterpret_cast<const int16_t*>(elements), static_cast<size_t>(len / 2));
            env->ReleaseByteArrayElements(pcmData, elements, JNI_ABORT);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeFeedReceivedPacket(
    JNIEnv* env, jobject thiz, jbyte originId, jbyte flag, jshort seqIndex, jbyteArray payload
) {
    if (gAudioEngine && payload) {
        jsize len = env->GetArrayLength(payload);
        jbyte* body = env->GetByteArrayElements(payload, nullptr);

        gAudioEngine->feedReceivedPacket(
            static_cast<uint8_t>(originId),
            static_cast<uint8_t>(flag),
            static_cast<uint16_t>(seqIndex),
            reinterpret_cast<const uint8_t*>(body),
            static_cast<size_t>(len)
        );

        env->ReleaseByteArrayElements(payload, body, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeSetVadMode(JNIEnv* env, jobject thiz, jint mode) {
    if (gAudioEngine) {
        gAudioEngine->setVadMode(mode);
    }
}

JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeSetMusicDucking(JNIEnv* env, jobject thiz, jboolean enabled) {
    if (gAudioEngine) {
        gAudioEngine->setMusicDucking(enabled == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeSetMyOriginId(JNIEnv* env, jobject thiz, jbyte originId) {
    if (gAudioEngine) {
        gAudioEngine->setMyOriginId(static_cast<uint8_t>(originId));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeSetMicMuted(JNIEnv* env, jobject thiz, jboolean muted) {
    if (gAudioEngine) {
        gAudioEngine->setMicMuted(muted == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeSetLocalMusicGain(JNIEnv* env, jobject thiz, jfloat gain) {
    if (gAudioEngine) {
        gAudioEngine->setLocalMusicGain(static_cast<float>(gain));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeSetPeerVolume(JNIEnv* env, jobject thiz, jbyte originId, jfloat volume) {
    if (gAudioEngine) {
        gAudioEngine->setPeerGain(static_cast<uint8_t>(originId), static_cast<float>(volume));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeSetDeviceIds(JNIEnv* env, jobject thiz, jint inputDeviceId, jint outputDeviceId) {
    if (gAudioEngine) {
        gAudioEngine->setDeviceIds(static_cast<int>(inputDeviceId), static_cast<int>(outputDeviceId));
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeIsAudioRunning(JNIEnv* env, jobject thiz) {
    if (gAudioEngine) {
        return gAudioEngine->isRunning() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_peersync_app_audio_AudioBridge_nativeResetPeerSeq(JNIEnv* env, jobject thiz, jbyte originId) {
    if (gAudioEngine) {
        gAudioEngine->resetPeerSeq(static_cast<uint8_t>(originId));
    }
}

}
