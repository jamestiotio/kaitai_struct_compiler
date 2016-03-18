package io.kaitai.struct.exprlang

import io.kaitai.struct.format.ProcessExpr

object DataType {
  abstract class IntWidth(val width: Int)
  case object Width1 extends IntWidth(1)
  case object Width2 extends IntWidth(2)
  case object Width4 extends IntWidth(4)
  case object Width8 extends IntWidth(8)

  sealed trait Endianness
  case object LittleEndian extends Endianness {
    override def toString = "le"
  }
  case object BigEndian extends Endianness {
    override def toString = "be"
  }

  sealed trait BaseType

  abstract class IntType extends BaseType {
    def apiCall: String
  }
  case object CalcIntType extends IntType {
    override def apiCall: String = ???
  }
  case class Int1Type(signed: Boolean) extends IntType {
    override def apiCall: String = if (signed) "s1" else "u1"
  }
  case class IntMultiType(signed: Boolean, width: IntWidth, endian: Endianness) extends IntType {
    override def apiCall: String = {
      val ch1 = if (signed) 's' else 'u'
      s"${ch1}${width.width}${endian.toString}"
    }
  }

  trait Processing {
    def process: Option[ProcessExpr]
  }

  abstract class BytesType extends BaseType with Processing
  case object CalcBytesType extends StrType
  case class FixedBytesType(contents: Array[Byte], override val process: Option[ProcessExpr]) extends BytesType
  case class BytesEosType(override val process: Option[ProcessExpr]) extends BytesType
  case class BytesLimitType(s: Ast.expr, override val process: Option[ProcessExpr]) extends BytesType

  abstract class StrType extends BaseType
  case object CalcStrType extends StrType
  case class StrEosType(encoding: String) extends StrType
  case class StrByteLimitType(s: Ast.expr, encoding: String) extends StrType
  case class StrZType(
    encoding: String,
    terminator: Int,
    include: Boolean,
    consume: Boolean,
    eosError: Boolean
  ) extends StrType

  case object BooleanType extends BaseType
  case class ArrayType(elType: BaseType) extends BaseType
  case class MapType(keyType: BaseType, valueType: BaseType) extends BaseType

  abstract class UserType(val name: List[String]) extends BaseType
  case class UserTypeInstream(_name: List[String]) extends UserType(_name)
  case class UserTypeByteLimit(_name: List[String], size: Ast.expr) extends UserType(_name)
  case class UserTypeEos(_name: List[String]) extends UserType(_name)

  case class EnumType(name: String, basedOn: IntType) extends BaseType

  private val ReIntType = """([us])(2|4|8)(le|be)?""".r

  def yamlToDataType(
    dt: String,
    defaultEndian: Option[Endianness],
    size: Option[Ast.expr],
    sizeEos: Boolean,
    encoding: Option[String],
    terminator: Int,
    include: Boolean,
    consume: Boolean,
    eosError: Boolean,
    contents: Option[Array[Byte]],
    enumRef: Option[String],
    process: Option[ProcessExpr]
  ): BaseType = {
    val r = dt match {
      case "u1" => Int1Type(false)
      case "s1" => Int1Type(true)
      case ReIntType(signStr, widthStr, endianStr) =>
        IntMultiType(
          signStr match {
            case "s" => true
            case "u" => false
          },
          widthStr match {
            case "2" => Width2
            case "4" => Width4
            case "8" => Width8
          },
          endianStr match {
            case "le" => LittleEndian
            case "be" => BigEndian
            case null =>
              defaultEndian match {
                case Some(e) => e
                case None => throw new RuntimeException(s"unable to use integer type '${dt}' without default endianness")
              }
          }
        )
      case null =>
        contents match {
          case Some(c) => FixedBytesType(c, process)
          case _ =>
            ((size, sizeEos)) match {
              case (Some(bs: Ast.expr), false) => BytesLimitType(bs, process)
              case (None, true) => BytesEosType(process)
              case (None, false) =>
                throw new RuntimeException("no type: either \"size\" or \"size-eos\" must be specified")
              case (Some(_), true) =>
                throw new RuntimeException("no type: only one of \"size\" or \"size-eos\" must be specified")
            }
        }
      case "str" =>
        if (encoding.isEmpty)
          throw new RuntimeException("type str: encoding must be specified")
        ((size, sizeEos)) match {
          case (Some(bs: Ast.expr), false) => StrByteLimitType(bs, encoding.get)
          case (None, true) => StrEosType(encoding.get)
          case (None, false) =>
            throw new RuntimeException("type str: either \"size\" or \"size-eos\" must be specified")
          case (Some(_), true) =>
            throw new RuntimeException("type str: only one of \"size\" or \"size-eos\" must be specified")
        }
      case "strz" =>
        if (encoding.isEmpty)
          throw new RuntimeException("type str: encoding must be specified")
        StrZType(encoding.get, terminator, include, consume, eosError)
      case _ =>
        val dtl = dt.split("::", -1).toList
        ((size, sizeEos)) match {
          case (Some(bs: Ast.expr), false) => UserTypeByteLimit(dtl, bs)
          case (None, true) => UserTypeEos(dtl)
          case (None, false) => UserTypeInstream(dtl)
          case (Some(_), true) =>
            throw new RuntimeException("user type '" + dt + "': only one of \"size\" or \"size-eos\" must be specified")
        }
    }

    enumRef match {
      case Some(enumName) =>
        r match {
          case numType: IntType => EnumType(enumName, numType)
          case _ =>
            throw new RuntimeException(s"tried to resolve non-integer $r to enum")
        }
      case None =>
        r
    }
  }
}
