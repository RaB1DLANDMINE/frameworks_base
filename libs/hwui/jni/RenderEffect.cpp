/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "Bitmap.h"
#include "ColorFilter.h"
#include "GraphicsJNI.h"
#include "SkColor.h"
#include "SkBlendMode.h"
#include "SkImageFilter.h"
#include "SkImageFilters.h"
#include "SkPoint.h"
#include "SkGradientShader.h"
#include "graphics_jni_helpers.h"
#include "utils/Blur.h"

#include <algorithm>

using namespace android::uirenderer;

static sk_sp<SkImageFilter> maskGradientBlur(sk_sp<SkImageFilter> blurFilter,
        jfloat radiusLeft, jfloat radiusRight, jfloat radiusTop, jfloat radiusBottom) {
    constexpr float kMinGradientDelta = 0.5f;
    constexpr float kFadeDistance = 260.0f;

    float horizontalDelta = std::abs(radiusRight - radiusLeft);
    float verticalDelta = std::abs(radiusBottom - radiusTop);
    if (horizontalDelta < kMinGradientDelta && verticalDelta < kMinGradientDelta) {
        return blurFilter;
    }

    bool isVertical = verticalDelta >= horizontalDelta;
    bool fadeInFromStart = isVertical ? radiusTop < radiusBottom : radiusLeft < radiusRight;

    SkPoint points[2] = {
            {0.0f, 0.0f},
            {isVertical ? 0.0f : kFadeDistance, isVertical ? kFadeDistance : 0.0f},
    };
    SkColor colors[2] = {
            fadeInFromStart ? SK_ColorTRANSPARENT : SK_ColorWHITE,
            fadeInFromStart ? SK_ColorWHITE : SK_ColorTRANSPARENT,
    };
    sk_sp<SkShader> maskShader = SkGradientShader::MakeLinear(
            points, colors, nullptr, 2, SkTileMode::kClamp);
    sk_sp<SkImageFilter> maskFilter = SkImageFilters::Shader(std::move(maskShader));
    return SkImageFilters::Blend(SkBlendMode::kDstIn, std::move(blurFilter), std::move(maskFilter));
}

static jlong createOffsetEffect(
    JNIEnv* env,
    jobject,
    jfloat offsetX,
    jfloat offsetY,
    jlong inputFilterHandle
) {
    auto* inputFilter = reinterpret_cast<const SkImageFilter*>(inputFilterHandle);
    sk_sp<SkImageFilter> offset = SkImageFilters::Offset(offsetX, offsetY, sk_ref_sp(inputFilter));
    return reinterpret_cast<jlong>(offset.release());
}

static jlong createBlurEffect(JNIEnv* env , jobject, jfloat radiusX,
        jfloat radiusY, jlong inputFilterHandle, jint edgeTreatment) {
    auto* inputImageFilter = reinterpret_cast<SkImageFilter*>(inputFilterHandle);
    sk_sp<SkImageFilter> blurFilter =
            SkImageFilters::Blur(
                    Blur::convertRadiusToSigma(radiusX),
                    Blur::convertRadiusToSigma(radiusY),
                    static_cast<SkTileMode>(edgeTreatment),
                    sk_ref_sp(inputImageFilter),
                    nullptr);
    return reinterpret_cast<jlong>(blurFilter.release());
}

static jlong createGradientBlurEffect(JNIEnv* env, jobject, jfloat radiusLeft,
        jfloat radiusRight, jfloat radiusTop, jfloat radiusBottom, jfloat speed, jint blendMode,
        jint blendC, jint mixC, jlong inputFilterHandle) {
    auto* inputImageFilter = reinterpret_cast<SkImageFilter*>(inputFilterHandle);

    float radiusX = std::max(0.0f, std::max(radiusLeft, radiusRight));
    float radiusY = std::max(0.0f, std::max(radiusTop, radiusBottom));
    float blurScale = speed > 0.0f ? speed : 1.0f;

    sk_sp<SkImageFilter> blurFilter =
            SkImageFilters::Blur(
                    Blur::convertRadiusToSigma(radiusX * blurScale),
                    Blur::convertRadiusToSigma(radiusY * blurScale),
                    SkTileMode::kMirror,
                    sk_ref_sp(inputImageFilter),
                    nullptr);
    blurFilter = maskGradientBlur(
            std::move(blurFilter), radiusLeft, radiusRight, radiusTop, radiusBottom);
    return reinterpret_cast<jlong>(blurFilter.release());
}

static jlong createKawaseBlurEffect(JNIEnv* env, jobject, jfloat radius,
        jlong inputFilterHandle) {
    auto* inputImageFilter = reinterpret_cast<SkImageFilter*>(inputFilterHandle);
    float safeRadius = std::max(0.0f, radius);
    sk_sp<SkImageFilter> blurFilter =
            SkImageFilters::Blur(
                    Blur::convertRadiusToSigma(safeRadius),
                    Blur::convertRadiusToSigma(safeRadius),
                    SkTileMode::kMirror,
                    sk_ref_sp(inputImageFilter),
                    nullptr);
    return reinterpret_cast<jlong>(blurFilter.release());
}

static jlong createBitmapEffect(
    JNIEnv* env,
    jobject,
    jlong bitmapHandle,
    jfloat srcLeft,
    jfloat srcTop,
    jfloat srcRight,
    jfloat srcBottom,
    jfloat dstLeft,
    jfloat dstTop,
    jfloat dstRight,
    jfloat dstBottom
) {
    sk_sp<SkImage> image = android::bitmap::toBitmap(bitmapHandle).makeImage();
    SkRect srcRect = SkRect::MakeLTRB(srcLeft, srcTop, srcRight, srcBottom);
    SkRect dstRect = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);
    sk_sp<SkImageFilter> bitmapFilter = SkImageFilters::Image(
            image, srcRect, dstRect, SkSamplingOptions(SkFilterMode::kLinear));
    return reinterpret_cast<jlong>(bitmapFilter.release());
}

static jlong createColorFilterEffect(
    JNIEnv* env,
    jobject,
    jlong colorFilterHandle,
    jlong inputFilterHandle
) {
    auto colorFilter = android::uirenderer::ColorFilter::fromJava(colorFilterHandle);
    auto skColorFilter =
            colorFilter != nullptr ? colorFilter->getInstance() : sk_sp<SkColorFilter>();
    auto* inputFilter = reinterpret_cast<const SkImageFilter*>(inputFilterHandle);
    sk_sp<SkImageFilter> colorFilterImageFilter =
            SkImageFilters::ColorFilter(skColorFilter, sk_ref_sp(inputFilter), nullptr);
    return reinterpret_cast<jlong>(colorFilterImageFilter.release());
}

static jlong createBlendModeEffect(
    JNIEnv* env,
    jobject,
    jlong backgroundImageFilterHandle,
    jlong foregroundImageFilterHandle,
    jint blendmodeHandle
) {
    auto* backgroundFilter = reinterpret_cast<const SkImageFilter*>(backgroundImageFilterHandle);
    auto* foregroundFilter = reinterpret_cast<const SkImageFilter*>(foregroundImageFilterHandle);
    SkBlendMode blendMode = static_cast<SkBlendMode>(blendmodeHandle);
    sk_sp<SkImageFilter> xfermodeFilter = SkImageFilters::Blend(
            blendMode,
            sk_ref_sp(backgroundFilter),
            sk_ref_sp(foregroundFilter)
    );
    return reinterpret_cast<jlong>(xfermodeFilter.release());
}

static jlong createChainEffect(
    JNIEnv* env,
    jobject,
    jlong outerFilterHandle,
    jlong innerFilterHandle
) {
    auto* outerImageFilter = reinterpret_cast<const SkImageFilter*>(outerFilterHandle);
    auto* innerImageFilter = reinterpret_cast<const SkImageFilter*>(innerFilterHandle);
    sk_sp<SkImageFilter> composeFilter = SkImageFilters::Compose(
        sk_ref_sp(outerImageFilter),
        sk_ref_sp(innerImageFilter)
    );
    return reinterpret_cast<jlong>(composeFilter.release());
}

static jlong createShaderEffect(
    JNIEnv* env,
    jobject,
    jlong shaderHandle
) {
    auto* shader = reinterpret_cast<const SkShader*>(shaderHandle);
    sk_sp<SkImageFilter> shaderFilter = SkImageFilters::Shader(
          sk_ref_sp(shader), nullptr
    );
    return reinterpret_cast<jlong>(shaderFilter.release());
}

static inline int ThrowIAEFmt(JNIEnv* env, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    int ret = jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", fmt, args);
    va_end(args);
    return ret;
}

static jlong createRuntimeShaderEffect(JNIEnv* env, jobject, jlong shaderBuilderHandle,
                                       jstring inputShaderName) {
    SkRuntimeShaderBuilder* builder =
            reinterpret_cast<SkRuntimeShaderBuilder*>(shaderBuilderHandle);
    ScopedUtfChars name(env, inputShaderName);

    if (builder->child(name.c_str()).fChild == nullptr) {
        ThrowIAEFmt(env,
                    "unable to find a uniform with the name '%s' of the correct "
                    "type defined by the provided RuntimeShader",
                    name.c_str());
        return 0;
    }

    sk_sp<SkImageFilter> filter = SkImageFilters::RuntimeShader(*builder, name.c_str(), nullptr);
    return reinterpret_cast<jlong>(filter.release());
}

static void RenderEffect_safeUnref(SkImageFilter* filter) {
    SkSafeUnref(filter);
}

static jlong getRenderEffectFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&RenderEffect_safeUnref));
}

static const JNINativeMethod gRenderEffectMethods[] = {
        {"nativeGetFinalizer", "()J", (void*)getRenderEffectFinalizer},
        {"nativeCreateOffsetEffect", "(FFJ)J", (void*)createOffsetEffect},
        {"nativeCreateBlurEffect", "(FFJI)J", (void*)createBlurEffect},
        {"nativeCreateGradientBlurEffect", "(FFFFFIIIJ)J", (void*)createGradientBlurEffect},
        {"nativeCreateKawaseBlurEffect", "(FJ)J", (void*)createKawaseBlurEffect},
        {"nativeCreateBitmapEffect", "(JFFFFFFFF)J", (void*)createBitmapEffect},
        {"nativeCreateColorFilterEffect", "(JJ)J", (void*)createColorFilterEffect},
        {"nativeCreateBlendModeEffect", "(JJI)J", (void*)createBlendModeEffect},
        {"nativeCreateChainEffect", "(JJ)J", (void*)createChainEffect},
        {"nativeCreateShaderEffect", "(J)J", (void*)createShaderEffect},
        {"nativeCreateRuntimeShaderEffect", "(JLjava/lang/String;)J",
         (void*)createRuntimeShaderEffect}};

int register_android_graphics_RenderEffect(JNIEnv* env) {
    android::RegisterMethodsOrDie(env, "android/graphics/RenderEffect",
            gRenderEffectMethods, NELEM(gRenderEffectMethods));
    return 0;
}
