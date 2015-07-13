package sangria.execution

import org.parboiled2.Position
import sangria.ast
import sangria.parser.SourceMapper
import sangria.renderer.{SchemaRenderer, QueryRenderer}
import sangria.schema._
import sangria.validation._

import scala.util.{Success, Failure, Try}

class ValueExecutor[Input](schema: Schema[_, _], inputVars: Input, sourceMapper: Option[SourceMapper] = None)(implicit um: InputUnmarshaller[Input]) {
  def getVariableValues(definitions: List[ast.VariableDefinition]): Try[Map[String, Any]] = {
    val res = definitions.foldLeft(List[(String, Either[List[Violation], Any])]()) {
      case (acc, varDef) =>
        val value = schema.inputTypes.get(varDef.tpe.name)
          .map(getVariableValue(varDef, _, um.getRootMapValue(inputVars, varDef.name)))
          .getOrElse(Left(UnknownVariableTypeViolation(varDef.name, QueryRenderer.render(varDef.tpe)) :: Nil))

        value match {
          case s @ Right(Some(v)) => acc :+ (varDef.name, s)
          case Right(None) => acc
          case l: Left[_, _] => acc :+ (varDef.name, l)
        }
    }

    val (errors, values) = res.partition(_._2.isLeft)

    if (errors.nonEmpty) Failure(VariableCoercionError(errors.collect{case (name, Left(errors)) => errors}.flatten))
    else Success(Map(values.collect {case (name, Right(v)) => name -> v}: _*))
  }

  def getVariableValue(definition: ast.VariableDefinition, tpe: InputType[_], input: Option[um.LeafNode]): Either[List[Violation], Option[Any]] =
    if (isValidValue(tpe, input)) {
      val fieldPath = s"$$${definition.name}" :: Nil

      if (input.isEmpty || !um.isDefined(input.get))
        definition.defaultValue map (coerceAstValue(tpe, fieldPath, _, Map.empty)) getOrElse Right(None)
      else coerceInputValue(tpe, fieldPath, input.get)
    } else Left(VarTypeMismatchViolation(definition.name, QueryRenderer.render(definition.tpe), input map um.render) :: Nil)

  def isValidValue(tpe: InputType[_], input: Option[um.LeafNode]): Boolean = (tpe, input) match {
    case (OptionInputType(ofType), Some(value)) if um.isDefined(value) => isValidValue(ofType, Some(value))
    case (OptionInputType(_), _) => true
    case (ListInputType(ofType), Some(values)) if um.isArrayNode(values) =>
      um.getArrayValue(values).forall(v => isValidValue(ofType, v match {
        case opt: Option[um.LeafNode @unchecked] => opt
        case other => Option(other)
      }))
    case (ListInputType(ofType), Some(value)) if um.isDefined(value) =>
      isValidValue(ofType, value match {
        case opt: Option[um.LeafNode @unchecked] => opt
        case other => Option(other)
      })
    case (objTpe: InputObjectType[_], Some(valueMap)) if um.isMapNode(valueMap) =>
      objTpe.fields.forall(f => isValidValue(f.fieldType, um.getMapValue(valueMap, f.name)))
    case (scalar: ScalarType[_], Some(value)) if um.isScalarNode(value) => scalar.coerceUserInput(um.getScalarValue(value)).isRight
    case (enum: EnumType[_], Some(value)) if um.isScalarNode(value) => enum.coerceUserInput(um.getScalarValue(value)).isRight
    case _ => false
  }

  def resolveListValue(ofType: InputType[_], fieldPath: List[String], value: Either[List[Violation], Option[Any]], pos: Option[Position] = None) = value match {
    case r @ Right(v) if ofType.isInstanceOf[OptionInputType[_]] => r
    case Right(Some(v)) => Right(v)
    case Right(None) => Left(NullValueForNotNullTypeViolation(fieldPath, SchemaRenderer.renderTypeName(ofType), sourceMapper, pos) :: Nil)
    case l @ Left(_) => l
  }

  def resolveMapValue(ofType: InputType[_], fieldPath: List[String], fieldName: String, acc: Map[String, Either[List[Violation], Any]], value: Either[List[Violation], Option[Any]], pos: Option[Position] = None) = value match {
    case r @ Right(v) if ofType.isInstanceOf[OptionInputType[_]] => acc
    case Right(Some(v)) => acc.updated(fieldName, Right(v))
    case Right(None) => acc.updated(fieldName, Left(NullValueForNotNullTypeViolation(fieldPath, SchemaRenderer.renderTypeName(ofType), sourceMapper, pos) :: Nil))
    case l @ Left(_) => acc.updated(fieldName, l)
  }

  def coerceInputValue(tpe: InputType[_], fieldPath: List[String], input: um.LeafNode): Either[List[Violation], Option[Any]] = (tpe, input) match {
    case (OptionInputType(ofType), value) => coerceInputValue(ofType, fieldPath, value)
    case (ListInputType(ofType), values) if um.isArrayNode(values) =>
      val res = um.getArrayValue(values).map {
        case defined if um.isDefined(defined) => resolveListValue(ofType, fieldPath, coerceInputValue(ofType, fieldPath, defined))
        case _ => resolveListValue(ofType, fieldPath, Right(None))
      }

      val (errors, successes) = res.partition(_.isLeft)

      if (errors.nonEmpty) Left(errors.collect{case Left(errors) => errors}.toList.flatten)
      else Right(Some(successes.collect {case Right(v) => v}))
    case (ListInputType(ofType), value) =>
      resolveListValue(ofType, fieldPath, coerceInputValue(ofType, fieldPath, value)) match {
        case Right(v) => Right(Some(Seq(v)))
        case l @ Left(violations) => Left(violations)
      }
    case (objTpe: InputObjectType[_], valueMap) if um.isMapNode(valueMap) =>
      val res = objTpe.fields.foldLeft(Map.empty[String, Either[List[Violation], Any]]) {
        case (acc, field) => um.getMapValue(valueMap, field.name) match {
          case Some(defined) if um.isDefined(defined) =>
            resolveMapValue(field.fieldType, fieldPath, field.name, acc,
              coerceInputValue(field.fieldType, fieldPath :+ field.name, defined))
          case _ => resolveMapValue(field.fieldType, fieldPath, field.name, acc, Right(None))
        }
      }

      val errors = res.collect{case (_, Left(errors)) => errors}.toList.flatten

      if (errors.nonEmpty) Left(errors)
      else Right(Some(res mapValues (_.right.get)))
    case (scalar: ScalarType[_], value) if um.isScalarNode(value) =>
      scalar.coerceUserInput(um.getScalarValue(value))
          .fold(violation => Left(FieldCoercionViolation(fieldPath, violation, None, None) :: Nil), v => Right(Some(v)))
    case (enum: EnumType[_], value) if um.isScalarNode(value) =>
      enum.coerceUserInput(um.getScalarValue(value))
          .fold(violation => Left(FieldCoercionViolation(fieldPath, violation, None, None) :: Nil), v => Right(Some(v)))
  }

  def coerceAstValue(tpe: InputType[_], fieldPath: List[String], input: ast.Value, variables: Map[String, Any]): Either[List[Violation], Option[Any]] = (tpe, input) match {
    // Note: we're not doing any checking that this variable is correct. We're
    // assuming that this query has been validated and the variable usage here
    // is of the correct type.
    case (_, ast.VariableValue(name, _)) => Right(variables get name)
    case (OptionInputType(ofType), value) => coerceAstValue(ofType, fieldPath, value, variables)
    case (ListInputType(ofType), ast.ArrayValue(values, _)) =>
      val res = values.map {v => resolveListValue(ofType, fieldPath, coerceAstValue(ofType, fieldPath, v, variables), v.position)}
      val (errors, successes) = res.partition(_.isLeft)

      if (errors.nonEmpty) Left(errors.collect{case Left(errors) => errors}.toList.flatten)
      else Right(Some(successes.collect {case Right(v) => v}))
    case (ListInputType(ofType), value) =>
      resolveListValue(ofType, fieldPath, coerceAstValue(ofType, fieldPath, value, variables), value.position) match {
        case Right(v) => Right(Some(Seq(v)))
        case l @ Left(violations) => Left(violations)
      }
    case (objTpe: InputObjectType[_], ast.ObjectValue(fieldList, objPos)) =>
      val astFields = fieldList groupBy (_.name) mapValues (_.head)
      val res = objTpe.fields.foldLeft(Map.empty[String, Either[List[Violation], Any]]) {
        case (acc, field) => astFields get field.name match {
          case Some(defined) =>
            resolveMapValue(field.fieldType, fieldPath, field.name, acc,
              coerceAstValue(field.fieldType, fieldPath :+ field.name, defined.value, variables), defined.value.position)
          case _ => resolveMapValue(field.fieldType, fieldPath, field.name, acc, Right(None), objPos)
        }
      }

      val errors = res.collect{case (_, Left(errors)) => errors}.toList.flatten

      if (errors.nonEmpty) Left(errors)
      else Right(Some(res mapValues (_.right.get)))
    case (objTpe: InputObjectType[_], value) =>
      Left(InputObjectTypeMismatchViolation(fieldPath, SchemaRenderer.renderTypeName(objTpe), QueryRenderer.render(value), sourceMapper, value.position) :: Nil)
    case (scalar: ScalarType[_], value) =>
      scalar.coerceInput(value)
          .fold(violation => Left(FieldCoercionViolation(fieldPath, violation, sourceMapper, value.position) :: Nil), v => Right(Some(v)))
    case (enum: EnumType[_], value) =>
      enum.coerceUserInput(value)
          .fold(violation => Left(FieldCoercionViolation(fieldPath, violation, sourceMapper, value.position) :: Nil), v => Right(Some(v)))
  }
}
