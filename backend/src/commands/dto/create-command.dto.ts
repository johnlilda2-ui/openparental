import { CommandType } from '@prisma/client';
import { IsEnum, IsNotEmpty, IsObject, IsOptional, IsString, MaxLength } from 'class-validator';

export class CreateCommandDto {
  @IsEnum(CommandType)
  type!: CommandType;

  @IsOptional()
  @IsObject()
  payload?: Record<string, unknown>;

  /** Optional idempotency key, scoped to this device and command type. */
  @IsOptional()
  @IsString()
  @IsNotEmpty()
  @MaxLength(128)
  idempotencyKey?: string;
}
