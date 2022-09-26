package com.github.compiler.processor

import com.github.annotation.Autogen
import com.github.annotation.ThreadScheduler
import com.github.annotation.ThreadSchedulerType
import com.github.compiler.utils.*
import com.squareup.javapoet.*
import java.lang.Deprecated
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.*
import javax.lang.model.type.DeclaredType
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import kotlin.Any
import kotlin.Boolean
import kotlin.String

@SupportedOptions("AutogenRepository")
@SupportedAnnotationTypes("com.github.annotation.Autogen")
class AutogenRepositoryProcessor : AbstractProcessor() {
    private lateinit var mFiler: Filer
    private lateinit var mElementUtils: Elements
    private lateinit var mTypeUtils: Types
    private lateinit var absRepositoryClass: ClassName
    private lateinit var apiRequestHelper: ClassName
    private lateinit var rxObservable: TypeElement
    private lateinit var baseLibBaseResponse: TypeElement
    private var mServiceName: String = ""
    private var mApiServicePackage: String = ""
    private var requestPackage: String = ""
    private var requestSimpleName: String = ""

    /**
     * 初始化常用工具
     */
    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        mTypeUtils = processingEnv.typeUtils
        mElementUtils = processingEnv.elementUtils
        mFiler = processingEnv.filer

        //获取AbsRepository和ApiRequestHelper className
        absRepositoryClass =
            mElementUtils.getTypeElement(ABS_REPOSITORY_PACKAGE + DOT + ABS_REPOSITORY_SIMPLE_NAME)
                .asClassName()
        apiRequestHelper =
            mElementUtils.getTypeElement(API_REQUEST_HELPER_PACKAGE + DOT + API_REQUEST_HELPER_SIMPLE_NAME)
                .asClassName()

        rxObservable = mElementUtils.getTypeElement(RX_JAVA_OBSERVABLE_CLASS_NAME)
        baseLibBaseResponse =
            mElementUtils.getTypeElement(BASE_LIB_BASE_RESPONSE_CLASS_NAME)
    }


    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latest()
    }


    override fun process(
        annotations: MutableSet<out TypeElement>?,
        roundEnv: RoundEnvironment
    ): Boolean {
        val elementsAnnotatedWith = roundEnv.getElementsAnnotatedWith(Autogen::class.java)
        elementsAnnotatedWith.forEach { element ->
            if (element.kind == ElementKind.INTERFACE) {//必须声明在接口上
                analyzeElement(element)
            }
        }
        //声明的注解不需要后续注解处理器再去处理
        return true
    }

    /**
     * 解析Element，根据解析结果生成Request和Repository
     */
    private fun analyzeElement(element: Element) {
        mServiceName = element.simpleName.toString()
        //获取当前注解修饰元素所在的包
        mApiServicePackage = mElementUtils.getPackageOf(element).qualifiedName.toString()
        generateRequestImpl(element)
        generateRepositoryImpl(element)
    }

    /**
     * 生成Request class
     */
    private fun generateRequestImpl(element: Element) {
        val host = element.getAnnotation(Autogen::class.java).host
        //生成文件所在包名以及类名
        requestPackage = AutogenUtils.getRequestPackageName(mApiServicePackage)
        requestSimpleName = AutogenUtils.getRequestClassName(mServiceName)
        //创建成员变量 ApiRequestHelper 和注解修饰的ApiService
        val apiHelper = FieldSpec.builder(
            apiRequestHelper,
            REQUEST_HELPER_VARIABLE_NAME,
            Modifier.PRIVATE,
            Modifier.STATIC,
            Modifier.FINAL
        ).initializer("ApiRequestHelper.getInstance()").build()

        val initializerString =
            if (host.isEmpty())
                "$REQUEST_HELPER_VARIABLE_NAME.createService($mServiceName.class)"
            else
                "$REQUEST_HELPER_VARIABLE_NAME.createService($mServiceName.class,\"$host\")"
        val service = FieldSpec.builder(
            element.asType().asTypeName(),
            SERVICE_VARIABLE_NAME,
            Modifier.PRIVATE,
            Modifier.STATIC,
            Modifier.FINAL
        ).initializer(initializerString).build()
        //创建Request类
        JavaFile.builder(
            requestPackage,
            TypeSpec.classBuilder(requestSimpleName)
                .addField(apiHelper)
                .addField(service)//添加成员变量
                .addModifiers(Modifier.PUBLIC)
                .addMethods(generateRequestFunctions(element))//添加成员函数
                .build(),
        ).build().writeTo(mFiler)

    }


    /**
     * 生成request 函数集合
     */
    private fun generateRequestFunctions(element: Element): ArrayList<MethodSpec> {
        val requestFunctionLis = arrayListOf<MethodSpec>()
        //返回包含的元素
        element.enclosedElements.forEach { subElement ->
            if (subElement.kind == ElementKind.METHOD && subElement is ExecutableElement) {
                val requestFunSpecBuilder = createFunctionBuilder(
                    subElement,
                    SERVICE_VARIABLE_NAME,
                    createDeprecatedApiAnnotation(subElement)
                ).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                requestFunctionLis.add(requestFunSpecBuilder.build())
            }
        }
        return requestFunctionLis
    }

    /**
     * 生成Repository class
     */
    private fun generateRepositoryImpl(element: Element) {
        //生成文件所在包名以及类名
        val repositoryPackage = AutogenUtils.getRepositoryPackageName(mApiServicePackage)
        val repositorySimpleName = AutogenUtils.getRepositoryClassName(mServiceName)
        //创建Repository类
        JavaFile.builder(
            repositoryPackage, TypeSpec.classBuilder(repositorySimpleName)
                .superclass(absRepositoryClass)
                .addMethods(generateRepositoryFunctions(element))
                .addModifiers(Modifier.PUBLIC)
                .build()
        )
            .build().writeTo(mFiler)

    }

    /**
     * 生成Repository 函数集合
     */

    private fun generateRepositoryFunctions(element: Element): ArrayList<MethodSpec> {
        val repositoryFunctionList = arrayListOf<MethodSpec>()
        //返回包含的元素
        element.enclosedElements.forEach { subElement ->
            if (subElement.kind == ElementKind.METHOD && subElement is ExecutableElement) {
                //是否关闭线程调度
                val threadScheduler = subElement.getAnnotation(ThreadScheduler::class.java)
                //如果ThreadScheduler为空，则 根据返回值类型采用默认处理结果
                var threadSchedulerType: ThreadSchedulerType =
                    threadScheduler?.threadScheduler ?: ThreadSchedulerType.DEFAULT
                var isObservableReturn = false
                var isBaseResponseReturn = false
                var returnTypeName: TypeName? = null


                //如果是需要处理请求结果的类型，获取处理结果后的返回值类型 returnTypeName
                when (threadSchedulerType) {
                    ThreadSchedulerType.DEFAULT,
                    ThreadSchedulerType.HANDLE_RESULT_TO_MAIN,
                    ThreadSchedulerType.HANDLE_RESULT_TO_IO,
                    ThreadSchedulerType.ONLY_HANDLE_RESULT -> {
                        val returnType = subElement.returnType//获取返回值类型
                        if (returnType is DeclaredType) {
                            //检查是否以RxJava中的Observable作为返回值
                            isObservableReturn = returnType.asElement().equals(rxObservable)
                            if (isObservableReturn) {
                                returnType.typeArguments.forEach { observableArgument ->
                                    // 检查Observable是否以baseLib中的BaseResponse作为泛型
                                    isBaseResponseReturn = mTypeUtils.asElement(observableArgument)
                                        .equals(baseLibBaseResponse)
                                    if (isBaseResponseReturn && observableArgument is DeclaredType) {
                                        //取出BaseResponse中的泛型作为RxJava.Observable泛型重新包装为Repository的返回参数
                                        returnTypeName = ParameterizedTypeName.get(
                                            rxObservable.asClassName(),
                                            observableArgument.typeArguments[0].asTypeName()
                                        )
                                    } else {
                                        System.err.println("返回值类型和线程调度器不匹配 mServiceName：$mServiceName  elementName:${subElement.simpleName}   isBaseResponseReturn : $isBaseResponseReturn  threadSchedulerType  $threadSchedulerType ")
                                        threadSchedulerType = ThreadSchedulerType.DEFAULT
                                    }
                                }
                            } else {
                                System.err.println("返回值类型和线程调度器不匹配  mServiceName：$mServiceName   elementName:${subElement.simpleName}  isObservableReturn : $isObservableReturn  threadSchedulerType  $threadSchedulerType  ")
                                threadSchedulerType = ThreadSchedulerType.DEFAULT
                            }
                        }
                    }
                    else -> {}
                }
                /**
                 * 返回值类型默认操作:
                 * 1. io.reactivex.Observable<?> 回调至mainThread
                 * 2. io.reactivex.Observable<BaseResponse<?>> 解析BaseResponse返回他的泛型类型，并回调至mainThread
                 * 3. 如果不使用 io.reactivex.Observable<?>，则不处理结果
                 *
                 */
                //如果是 DEFAULT
                if (threadSchedulerType == ThreadSchedulerType.DEFAULT) {
                    //并且 是以Observable作为返回值，则默认使用线程调度至Main = SWITCH_MAIN
                    if (isObservableReturn)
                        threadSchedulerType = ThreadSchedulerType.SWITCH_MAIN
                    //并且如果 是BaseResponse返回值，则默认处理返回值结果并且回调只Main =
                    if (isBaseResponseReturn) {
                        threadSchedulerType = ThreadSchedulerType.HANDLE_RESULT_TO_MAIN
                    }
                }
                val repositoryFunSpecBuilder = createFunctionBuilder(
                    subElement,
                    ClassName.get(requestPackage, requestSimpleName),
                    createDeprecatedApiAnnotation(subElement),
                    threadSchedulerType,
                    returnTypeName
                )
                repositoryFunSpecBuilder.addModifiers(Modifier.PUBLIC)
                repositoryFunctionList.add(repositoryFunSpecBuilder.build())
            }
        }
        return repositoryFunctionList

    }

    /**
     * 创建FunctionBuilder
     */
    private fun createFunctionBuilder(
        subElement: ExecutableElement,
        objectName: Any,
        annotationSpec: AnnotationSpec? = null,
        threadSchedulerType: ThreadSchedulerType = ThreadSchedulerType.DO_NOT_HANDLE,
        returnType: TypeName? = null
    ): MethodSpec.Builder {
        String
        //创建函数
        val functionName = subElement.simpleName.toString()
        val functionParameters = subElement.parameters
        val funSpecBuilder = MethodSpec.methodBuilder(functionName)
        //如果处理请求结果返回值，则需要根据处理的结果重新设置返回值
        if ((threadSchedulerType == ThreadSchedulerType.HANDLE_RESULT_TO_MAIN || threadSchedulerType == ThreadSchedulerType.HANDLE_RESULT_TO_IO || threadSchedulerType == ThreadSchedulerType.ONLY_HANDLE_RESULT) && returnType != null) {
            funSpecBuilder.returns(returnType)
        } else {
            funSpecBuilder.returns(subElement.returnType.asTypeName())
        }
        val statementSB = StringBuilder(
            when (objectName) {
                is String -> "return ${"$"}N."
                is ClassName -> "return ${"$"}T."
                else -> ""
            } + functionName
        )
        if (functionParameters != null && functionParameters.size > 0) {
            statementSB.append("(")
            functionParameters.forEach { params ->
                funSpecBuilder.addParameter(
                    params.asType().asTypeName(),
                    params.simpleName.toString()
                )
                statementSB.append(params.simpleName.toString())
                    .append(",")
            }
            statementSB.delete(
                statementSB.length - 1,
                statementSB.length
            )
            statementSB.append(")")
        } else {
            statementSB.append("()")
        }
        //只有在Repository类里才需要添加
        when (threadSchedulerType) {
            ThreadSchedulerType.HANDLE_RESULT_TO_MAIN -> statementSB.append(".compose(handleResult())")
            ThreadSchedulerType.SWITCH_MAIN -> statementSB.append(".compose(applySchedulers())")
            ThreadSchedulerType.HANDLE_RESULT_TO_IO -> statementSB.append(".compose(handleResultToIO())")
            ThreadSchedulerType.SWITCH_IO -> statementSB.append(".compose(applySchedulersIO())")
            ThreadSchedulerType.ONLY_HANDLE_RESULT -> statementSB.append(".compose(onlyHandleResult())")
            else -> {}
        }

        funSpecBuilder.addStatement(statementSB.toString(), objectName)
        if (annotationSpec != null) {
            funSpecBuilder.addAnnotation(annotationSpec)
        }
        return funSpecBuilder
    }


    private fun createDeprecatedApiAnnotation(subElement: Element): AnnotationSpec? {
        val deprecated = subElement.getAnnotation(Deprecated::class.java)
        if (deprecated != null) {
            return AnnotationSpec.builder(Deprecated::class.java).build()
        }
        return null
    }

}