package com.github.compiler.processor

import com.github.annotation.Autogen
import com.github.annotation.CloseScheduler
import com.github.compiler.utils.*
import com.squareup.javapoet.*
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.*
import javax.lang.model.type.DeclaredType
import javax.lang.model.util.Elements
import java.lang.Deprecated
import javax.lang.model.util.Types

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
                val closeScheduler = subElement.getAnnotation(CloseScheduler::class.java)
                var isApplySchedulers = false
                var isHandleResult = false
                var returnTypeName: TypeName? = null
                if (closeScheduler == null) {//如果没有关闭 根据返回值类型判断使用哪个调度器
                    val returnType = subElement.returnType//获取返回值类型
                    if (returnType is DeclaredType) {
                        //检查是否以RxJava中的Observable作为返回值
                        isApplySchedulers = returnType.asElement().equals(rxObservable)
                        if (isApplySchedulers) {
                            returnType.typeArguments.forEach { observableArgument ->
                                // 检查Observable是否以baseLib中的BaseResponse作为泛型
                                isHandleResult = mTypeUtils.asElement(observableArgument)
                                    .equals(baseLibBaseResponse)
                                if (isHandleResult && observableArgument is DeclaredType) {
                                    //取出BaseResponse中的泛型作为RxJava.Observable泛型重新包装为Repository的返回参数
                                    returnTypeName = ParameterizedTypeName.get(
                                        rxObservable.asClassName(),
                                        observableArgument.typeArguments[0].asTypeName()
                                    )
                                }
                            }
                        }
                    }
                }
                val repositoryFunSpecBuilder = createFunctionBuilder(
                    subElement,
                    ClassName.get(requestPackage, requestSimpleName),
                    createDeprecatedApiAnnotation(subElement),
                    isApplySchedulers,
                    isHandleResult,
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
        isApplySchedulers: Boolean = false,
        isHandleResult: Boolean = false,
        returnType: TypeName? = null
    ): MethodSpec.Builder {
        String
        //创建函数
        val functionName = subElement.simpleName.toString()
        val functionParameters = subElement.parameters
        val funSpecBuilder = MethodSpec.methodBuilder(functionName)
        if (isHandleResult && returnType != null) {
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
        if (isApplySchedulers) {//只有在Repository类里才需要添加
            if (isHandleResult) {
                statementSB.append(".compose(handleResult())")
            } else {
                statementSB.append(".compose(applySchedulers())")
            }
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