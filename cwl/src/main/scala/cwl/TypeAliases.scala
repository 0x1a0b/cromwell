package cwl

import cwl.CommandLineTool._
import shapeless.{:+:, CNil}
import cwl.CwlType.CwlType
import io.circe.Json
import wom.types.{WomArrayType, WomType}

trait TypeAliases {

  type CwlAny =
    File :+:
      Json :+:
      CNil

  type WorkflowStepInputId = String

  type Requirement =
    InlineJavascriptRequirement :+:
      SchemaDefRequirement :+:
      DockerRequirement :+:
      SoftwareRequirement :+:
      InitialWorkDirRequirement :+:
      EnvVarRequirement :+:
      ShellCommandRequirement :+:
      ResourceRequirement :+:
      SubworkflowFeatureRequirement :+:
      ScatterFeatureRequirement :+:
      MultipleInputFeatureRequirement :+:
      StepInputExpressionRequirement :+:
      CNil

  // TODO WOM: Record Schema as well as Directories are not included because they're not supported yet, although they should eventually be.
  // Removing them saves some compile time when building decoders for this type (in CwlInputParsing)
  type MyriadInputValuePrimitives = 
    String :+:
    Int :+:
    Long :+:
    File :+:
    Float :+:
    Double :+:
    Boolean :+:
    CNil

  type MyriadInputValue =
    MyriadInputValuePrimitives :+:
      Array[MyriadInputValuePrimitives] :+:
      CNil

  type MyriadInputType =
    CwlType :+:
      InputRecordSchema :+:
      InputEnumSchema :+:
      InputArraySchema :+:
      String :+:
      Array[
        CwlType :+:
          InputRecordSchema :+:
          InputEnumSchema :+:
          InputArraySchema :+:
          String :+:
          CNil
        ] :+:
      CNil

  type MyriadOutputType =
    CwlType :+:
      OutputRecordSchema :+:
      OutputEnumSchema :+:
      OutputArraySchema :+:
      String :+:
      Array[
        CwlType :+:
          OutputRecordSchema :+:
          OutputEnumSchema :+:
          OutputArraySchema :+:
          String :+:
          CNil
        ] :+:
      CNil

  type MyriadCommandInputType =
    CwlType :+:
      CommandInputRecordSchema :+:
      CommandInputEnumSchema :+:
      CommandInputArraySchema :+:
      String :+:
      Array[
        CwlType  :+:
          CommandInputRecordSchema :+:
          CommandInputEnumSchema :+:
          CommandInputArraySchema :+:
          String :+:
          CNil
        ] :+:
      CNil
  
  type ScatterVariables = Option[String :+: Array[String] :+: CNil]
}

object MyriadInputType {
  object CwlType {
    def unapply(m: MyriadInputType): Option[CwlType] = {
      m.select[CwlType]
    }
  }

  object CwlInputArraySchema {
    def unapply(m: MyriadInputType): Option[InputArraySchema] = {
      m.select[InputArraySchema]
    }
  }

  object WomType {
    def unapply(m: MyriadInputType): Option[WomType] = m match {
      case CwlType(c) => Option(cwl.cwlTypeToWomType(c))
      case CwlInputArraySchema(c) => c.items.select[CwlType].map(inner => WomArrayType(cwl.cwlTypeToWomType(inner)))
      case _ => None
    }
  }
}

object MyriadOutputType {
  object CwlType {
    def unapply(m: MyriadOutputType): Option[CwlType] = {
      m.select[CwlType]
    }
  }

  object CwlOutputArraySchema {
    def unapply(m: MyriadOutputType): Option[OutputArraySchema] = {
      m.select[OutputArraySchema]
    }
  }

  object WomType {
    def unapply(m: MyriadOutputType): Option[WomType] = m match {
      case CwlType(c) => Option(cwl.cwlTypeToWomType(c))
      case CwlOutputArraySchema(c) => c.items.select[CwlType].map(inner => WomArrayType(cwl.cwlTypeToWomType(inner)))
      case _ => None
    }
  }
}
