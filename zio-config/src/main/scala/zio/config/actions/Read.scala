package zio.config.actions

import zio.config.ReadError.MissingValue
import zio.config.ReadErrors
import zio.config.{ Config, ConfigReport, ConfigSource, Details }
import zio.{ config, Ref, UIO, ZIO }

case class Read[A](run: ZIO[ConfigSource, ReadErrors, (ConfigReport, A)]) {}

object Read {
  // Read
  final def read[A](configuration: Config[A]): Read[A] = {
    def loop[B](
      configuration: Config[B],
      report: Ref[ConfigReport]
    ): ZIO[ConfigSource, ReadErrors, (ConfigReport, B)] =
      configuration match {
        case Config.Pure(value) => report.get.map(t => (t, value))

        case Config.Source(path, propertyType) =>
          for {
            value <- config
                      .getConfigValue(path)
                      .mapError(_ => ReadErrors(MissingValue(path)))
            r <- report
                  .update(_.addDetails(Details(path, value, propertyType.description)))
            result <- ZIO.fromEither(
                       propertyType
                         .read(path, value)
                         .fold(r => Left(ReadErrors(r)), e => Right((r, e)))
                     )

          } yield result

        case Config.MapEither(c, f, _) =>
          loop(c, report).flatMap {
            case (r, src) => ZIO.fromEither(f(src)).bimap(err => ReadErrors(err), res => (r, res))
          }

        case Config.Optional(c) =>
          report.get.flatMap(
            t =>
              loop(c, report).fold(
                _ => (t, None),
                success => (success._1, Some(success._2))
              )
          )

        case Config.OnError(c, f) =>
          ZIO.accessM[ConfigSource](
            _ =>
              loop(c, report)
                .foldM(
                  errors => report.get.map(r => (r, f(errors))),
                  UIO(_)
                )
          )

        case Config.Zip(left, right) =>
          loop(left, report).either
            .flatMap(
              res1 =>
                loop(right, report).either.map(
                  res2 =>
                    (res1, res2) match {
                      case (Right((_, a)), Right((report2, b))) => Right((report2, (a, b)))
                      case (Left(a), Right(_))                  => Left(a)
                      case (Right(_), Left(error))              => Left(error)
                      case (Left(err1), Left(err2))             => Left(ReadErrors.concat(err1, err2))
                    }
                )
            )
            .absolve

        case Config.Or(left, right) =>
          loop(left, report).either.flatMap(
            {
              case Right((r, a)) => ZIO.access(_ => (r, Left(a)))
              case Left(lerr) =>
                loop(right, report).either.flatMap(
                  {
                    case Right((r, b)) => ZIO.access(_ => (r, Right(b)))
                    case Left(rerr)    => ZIO.fail(ReadErrors.concat(lerr, rerr))
                  }
                )
            }
          )
      }

    Read(Ref.make(ConfigReport(Nil)).flatMap(report => loop(configuration, report)))
  }
}