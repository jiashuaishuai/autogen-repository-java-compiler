package com.github.compiler.utils

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeName
import java.lang.reflect.Type
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeMirror

const val DOT = "."
const val REQUEST_PACKAGE_NAME = "request"
const val REPOSITORY_PACKAGE_NAME = "repository"

const val ABS_REPOSITORY_PACKAGE = "com.baselib.model.repository"
const val ABS_REPOSITORY_SIMPLE_NAME = "AbsRepository"
const val API_REQUEST_HELPER_PACKAGE = "com.baselib.model.http"
const val API_REQUEST_HELPER_SIMPLE_NAME = "ApiRequestHelper"

const val SERVICE_VARIABLE_NAME = "apiService"
const val REQUEST_HELPER_VARIABLE_NAME = "apiRequestHelper"

const val RX_JAVA_OBSERVABLE_CLASS_NAME = "io.reactivex.Observable"
const val BASE_LIB_BASE_RESPONSE_CLASS_NAME = "com.baselib.model.response.BaseResponse"
fun TypeMirror.asTypeName(): TypeName = TypeName.get(this).annotated()
fun Type.asTypeName(): TypeName = TypeName.get(this)
fun TypeElement.asClassName(): ClassName = ClassName.get(this)