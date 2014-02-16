package scodec
package codecs

import scalaz.\/
import \/.{ left, right }
import scalaz.syntax.id._
import scalaz.syntax.std.option._

import scodec.bits.BitVector
import DiscriminatorCodec.Case

/**
 * Codec that supports the binary structure `tag ++ value` where the `tag` identifies the encoding/decoding of
 * the value.
 *
 * To build an instance of this codec, call [[discrimated]] and specify the tag type via the `by` method. Then
 * call one more more of the case combinators on this class, such as [[caseO]] or [[typecase]].
 *
 * @see [[discriminated]]
 */
final class DiscriminatorCodec[A, B] private[codecs] (by: Codec[B], cases: Vector[Case[A,B]]) extends Codec[A] {

  def caseO[R](tag: B)(f: A => Option[R])(inj: R => A)(cr: Codec[R]): DiscriminatorCodec[A, B] =
    appendCase(Case(left(tag), (f, inj, cr)))

  def caseO[R](tag: B, g: B => Boolean)(f: A => Option[R])(inj: R => A)(cr: Codec[R]): DiscriminatorCodec[A, B] =
    appendCase(Case(right(tag -> g), (f, inj, cr)))

  def ?[R](tag: B)(f: A => Option[R])(inj: R => A)(cr: Codec[R]): DiscriminatorCodec[A, B] =
    caseO(tag)(f)(inj)(cr)

  def ?[R](tag: B, g: B => Boolean)(f: A => Option[R])(inj: R => A)(cr: Codec[R]): DiscriminatorCodec[A, B] =
    caseO(tag, g)(f)(inj)(cr)

  def caseP[R](tag: B)(f: PartialFunction[A,R])(inj: R => A)(cr: Codec[R]): DiscriminatorCodec[A, B] =
    appendCase(Case(left(tag), (f.lift, inj, cr)))

  def caseP[R](tag: B, g: B => Boolean)(f: PartialFunction[A,R])(inj: R => A)(cr: Codec[R]): DiscriminatorCodec[A, B] =
    appendCase(Case(right(tag -> g), (f.lift, inj, cr)))

  def |[R](tag: B)(f: PartialFunction[A,R])(inj: R => A)(cr: Codec[R]): DiscriminatorCodec[A, B] =
    caseP(tag)(f)(inj)(cr)

  def |[R](tag: B, g: B => Boolean)(f: PartialFunction[A,R])(inj: R => A)(cr: Codec[R]): DiscriminatorCodec[A, B] =
    caseP(tag, g)(f)(inj)(cr)

  def subcaseP[R <: A](tag: B)(f: PartialFunction[A,R])(cr: Codec[R]): DiscriminatorCodec[A, B] =
    appendCase(Case(left(tag), (f.lift, (r: R) => r, cr)))

  def subcaseP[R <: A](tag: B, g: B => Boolean)(f: PartialFunction[A,R])(cr: Codec[R]): DiscriminatorCodec[A, B] =
    appendCase(Case(right(tag -> g), (f.lift, (r: R) => r, cr)))

  def \[R <: A](tag: B)(f: PartialFunction[A,R])(cr: Codec[R]): DiscriminatorCodec[A, B] =
    subcaseP(tag)(f)(cr)

  def \[R <: A](tag: B, g: B => Boolean)(f: PartialFunction[A,R])(cr: Codec[R]): DiscriminatorCodec[A, B] =
    subcaseP(tag, g)(f)(cr)

  def subcaseO[R <: A](tag: B)(f: A => Option[R])(cr: Codec[R]): DiscriminatorCodec[A, B] =
    appendCase(Case(left(tag), (f, (r: R) => r, cr)))

  def subcaseO[R <: A](tag: B, g: B => Boolean)(f: A => Option[R])(cr: Codec[R]): DiscriminatorCodec[A, B] =
    appendCase(Case(right(tag -> g), (f, (r: R) => r, cr)))

  def /[R <: A](tag: B)(f: A => Option[R])(cr: Codec[R]): DiscriminatorCodec[A, B] =
    subcaseO(tag)(f)(cr)

  def /[R <: A](tag: B, g: B => Boolean)(f: A => Option[R])(cr: Codec[R]): DiscriminatorCodec[A, B] =
    subcaseO(tag, g)(f)(cr)

  def typecase[R <: A: Manifest](tag: B, cr: Codec[R]): DiscriminatorCodec[A, B] = {
    val f: A => Option[R] = a => if (matchesClass[R](a)) Some(a.asInstanceOf[R]) else None
    appendCase(Case(left(tag), (f, (r: R) => r, cr)))
  }

  def typecase[R <: A: Manifest](tag: B, g: B => Boolean, cr: Codec[R]): DiscriminatorCodec[A, B] = {
    val f: A => Option[R] = a => if (matchesClass[R](a)) Some(a.asInstanceOf[R]) else None
    appendCase(Case(right(tag -> g), (f, (r: R) => r, cr)))
  }

  private def matchesClass[R: Manifest](a: A) = {
    val clazz = manifest[R] match {
      case Manifest.Byte => classOf[java.lang.Byte]
      case Manifest.Char => classOf[java.lang.Character]
      case Manifest.Short => classOf[java.lang.Short]
      case Manifest.Int => classOf[java.lang.Integer]
      case Manifest.Long => classOf[java.lang.Long]
      case Manifest.Float => classOf[java.lang.Float]
      case Manifest.Double => classOf[java.lang.Double]
      case Manifest.Boolean => classOf[java.lang.Boolean]
      case m => m.runtimeClass
    }
    clazz.isAssignableFrom(a.getClass)
  }

  private def appendCase(c: Case[A, B]): DiscriminatorCodec[A, B] =
    new DiscriminatorCodec[A, B](by, cases :+ c)

  private val matcher: B => (String \/ Case[A,B]) =
    if (cases.forall(_.condition.isLeft)) { // build a little table
      // we reverse the cases so earlier cases 'win' in event of overlap
      val tbl = cases.reverse.map(kase => kase.condition.swap.toOption.get -> kase).toMap
      b => tbl.get(b) match {
        case None => left(s"unknown discrimination tag $b")
        case Some(kase) => right(kase)
      }
    }
    else // this could be a bit smarter, but we fall back to linear scan
      b => cases.find(_.condition.fold(_ == b, _._2(b))) match {
        case None => left(s"unknown discrimination tag $b")
        case Some(kase) => right(kase)
      }

  def encode(a: A): String \/ BitVector =
    cases.iterator.flatMap { k =>
      val (cond, extract, codec0) = (k.condition, k.prism._1, k.prism._3)
      // Workaround for scalac existential bug - `codec0` should be a
      // `Codec[R]` for the same `R` returned by `extract`, but scalac doesn't realize this
      val codec = codec0.asInstanceOf[Codec[Any]]
      extract(a).map { r =>
        by.encode(k.representative)
          .flatMap { bits => codec.encode(r).map(bits ++ _) }
      }.map(List(_)).getOrElse(List())
    }.toStream.headOption match {
      case None => left(s"could not find matching case for $a")
      case Some(r) => r
    }

  def decode(bits: BitVector): String \/ (BitVector, A) = for {
    remb <- by.decode(bits)
    (rem, b) = remb
    k <- matcher(b)
    remr <- k.prism._3.decode(rem)
    (rem2,r) = remr
    // Note: Scalac doesn't realize that the `R => A` in `k.prism._2`
    // is the same as the `R` returned by the `Codec` in `k.prism._3`
    // even though the type of prism clearly should ensure this!
    // prism: (A => Option[R], R => A, Codec[R]) forSome { type R }
  } yield (rem2, k.prism._2.asInstanceOf[Any => A](r))

  override def toString = s"discriminated($by)"
}

/** Companion for [[Discriminator]]. */
private[codecs] object DiscriminatorCodec {

  private[codecs] case class Case[A,B](
    condition: B \/ (B, B => Boolean), // either a literal `B`, or a `B` predicate
    prism: (A => Option[R], R => A, Codec[R]) forSome { type R } // `R` is existential
  ) {
    def representative: B = condition.fold(identity, _._1)
    // make sure that if condition is (x: X, f: X => Boolean), that
    // `f(x)` is true, otherwise this case will fail to match itself
    // on decoding!
    condition.toOption.foreach { case (representative, matches) =>
      matches(representative) ||
      sys.error(s"representative failed predicate: $representative")
    }
  }
}