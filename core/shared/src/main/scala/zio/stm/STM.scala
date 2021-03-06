/*
 * Copyright 2017-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.stm

import java.util.{ HashMap => MutableMap }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }

import com.github.ghik.silencer.silent
import zio.{ CanFail, Fiber, IO, UIO }
import zio.internal.{ Platform, Stack, Sync }

import scala.util.{ Failure, Success, Try }
import scala.annotation.tailrec

/**
 * `STM[E, A]` represents an effect that can be performed transactionally,
 * resulting in a failure `E` or a value `A`.
 *
 * {{{
 * def transfer(receiver: TRef[Int],
 *              sender: TRef[Int], much: Int): UIO[Int] =
 *   STM.atomically {
 *     for {
 *       balance <- sender.get
 *       _       <- STM.check(balance >= much)
 *       _       <- receiver.update(_ + much)
 *       _       <- sender.update(_ - much)
 *       newAmnt <- receiver.get
 *     } yield newAmnt
 *   }
 *
 *   val action: UIO[Int] =
 *     for {
 *       t <- STM.atomically(TRef.make(0).zip(TRef.make(20000)))
 *       (receiver, sender) = t
 *       balance <- transfer(receiver, sender, 1000)
 *     } yield balance
 * }}}
 *
 * Software Transactional Memory is a technique which allows composition
 *  of arbitrary atomic operations. It is the software analog of transactions in database systems.
 *
 * The API is lifted directly from the Haskell package Control.Concurrent.STM although the implementation does not
 *  resemble the Haskell one at all.
 *  [[http://hackage.haskell.org/package/stm-2.5.0.0/docs/Control-Concurrent-STM.html]]
 *
 *  STM in Haskell was introduced in:
 *  Composable memory transactions, by Tim Harris, Simon Marlow, Simon Peyton Jones, and Maurice Herlihy, in ACM
 *  Conference on Principles and Practice of Parallel Programming 2005.
 * [[https://www.microsoft.com/en-us/research/publication/composable-memory-transactions/]]
 *
 * See also:
 * Lock Free Data Structures using STMs in Haskell, by Anthony Discolo, Tim Harris, Simon Marlow, Simon Peyton Jones,
 * Satnam Singh) FLOPS 2006: Eighth International Symposium on Functional and Logic Programming, Fuji Susono, JAPAN,
 *  April 2006
 *  [[https://www.microsoft.com/en-us/research/publication/lock-free-data-structures-using-stms-in-haskell/]]
 *
 */
final class STM[+E, +A] private[stm] (
  private val exec: (STM.internal.Journal, Fiber.Id, AtomicLong) => STM.internal.TExit[E, A]
) extends AnyVal { self =>
  import STM.internal.{ prepareResetJournal, TExit }

  /**
   * Sequentially zips this value with the specified one.
   */
  def <*>[E1 >: E, B](that: => STM[E1, B]): STM[E1, (A, B)] =
    self zip that

  /**
   * Sequentially zips this value with the specified one, discarding the
   * second element of the tuple.
   */
  def <*[E1 >: E, B](that: => STM[E1, B]): STM[E1, A] =
    self zipLeft that

  /**
   * Sequentially zips this value with the specified one, discarding the
   * first element of the tuple.
   */
  def *>[E1 >: E, B](that: => STM[E1, B]): STM[E1, B] =
    self zipRight that

  /**
   * Feeds the value produced by this effect to the specified function,
   * and then runs the returned effect as well to produce its results.
   */
  def >>=[E1 >: E, B](f: A => STM[E1, B]): STM[E1, B] =
    self flatMap f

  /**
   * Maps the success value of this effect to the specified constant value.
   */
  def as[B](b: => B): STM[E, B] = self map (_ => b)

  /**
   * Maps the error value of this effect to the specified constant value.
   */
  def asError[E1](e: => E1)(implicit ev: CanFail[E]): STM[E1, A] =
    self mapError (_ => e)

  /**
   * Simultaneously filters and maps the value produced by this effect.
   */
  def collect[B](pf: PartialFunction[A, B]): STM[E, B] =
    collectM(pf.andThen(STM.succeed(_)))

  /**
   * Simultaneously filters and flatMaps the value produced by this effect.
   * Continues on the effect returned from pf.
   */
  def collectM[E1 >: E, B](pf: PartialFunction[A, STM[E1, B]]): STM[E1, B] =
    self.continueWithM {
      case TExit.Fail(e)    => STM.fail(e)
      case TExit.Succeed(a) => if (pf.isDefinedAt(a)) pf(a) else STM.retry
      case TExit.Retry      => STM.retry
    }

  /**
   * Commits this transaction atomically.
   */
  def commit: IO[E, A] = STM.atomically(self)

  /**
   * Converts the failure channel into an `Either`.
   */
  def either(implicit ev: CanFail[E]): STM[Nothing, Either[E, A]] =
    fold(Left(_), Right(_))

  /**
   * Executes the specified finalization transaction whether or
   * not this effect succeeds. Note that as with all STM transactions,
   * if the full transaction fails, everything will be rolled back.
   */
  def ensuring(finalizer: STM[Nothing, Any]): STM[E, A] =
    foldM(e => finalizer *> STM.fail(e), a => finalizer *> STM.succeed(a))

  /**
   * Tries this effect first, and if it fails, succeeds with the specified
   * value.
   */
  def fallback[A1 >: A](a: => A1)(implicit ev: CanFail[E]): STM[Nothing, A1] =
    fold(_ => a, identity)

  /**
   * Filters the value produced by this effect, retrying the transaction until
   * the predicate returns true for the value.
   */
  def filter(f: A => Boolean): STM[E, A] =
    collect {
      case a if f(a) => a
    }

  /**
   * Feeds the value produced by this effect to the specified function,
   * and then runs the returned effect as well to produce its results.
   */
  def flatMap[E1 >: E, B](f: A => STM[E1, B]): STM[E1, B] =
    self.continueWithM {
      case TExit.Succeed(a) => f(a)
      case TExit.Fail(e)    => STM.fail(e)
      case TExit.Retry      => STM.retry
    }

  /**
   * Flattens out a nested `STM` effect.
   */
  def flatten[E1 >: E, B](implicit ev: A <:< STM[E1, B]): STM[E1, B] =
    self flatMap ev

  /**
   * Folds over the `STM` effect, handling both failure and success, but not
   * retry.
   */
  def fold[B](f: E => B, g: A => B)(implicit ev: CanFail[E]): STM[Nothing, B] =
    self.continueWithM {
      case TExit.Fail(e)    => STM.succeed(f(e))
      case TExit.Succeed(a) => STM.succeed(g(a))
      case TExit.Retry      => STM.retry
    }

  /**
   * Effectfully folds over the `STM` effect, handling both failure and
   * success.
   */
  def foldM[E1, B](f: E => STM[E1, B], g: A => STM[E1, B])(implicit ev: CanFail[E]): STM[E1, B] =
    self.continueWithM {
      case TExit.Fail(e)    => f(e)
      case TExit.Succeed(a) => g(a)
      case TExit.Retry      => STM.retry
    }

  /**
   * Returns a new effect that ignores the success or failure of this effect.
   */
  def ignore: STM[Nothing, Unit] = self.either.unit

  /**
   * Maps the value produced by the effect.
   */
  def map[B](f: A => B): STM[E, B] =
    self.continueWithM {
      case TExit.Succeed(a) => STM.succeed(f(a))
      case TExit.Fail(e)    => STM.fail(e)
      case TExit.Retry      => STM.retry
    }

  /**
   * Maps from one error type to another.
   */
  def mapError[E1](f: E => E1)(implicit ev: CanFail[E]): STM[E1, A] =
    self.continueWithM {
      case TExit.Succeed(a) => STM.succeed(a)
      case TExit.Fail(e)    => STM.fail(f(e))
      case TExit.Retry      => STM.retry
    }

  /**
   * Converts the failure channel into an `Option`.
   */
  def option(implicit ev: CanFail[E]): STM[Nothing, Option[A]] =
    fold(_ => None, Some(_))

  /**
   * Tries this effect first, and if it fails, tries the other effect.
   */
  def orElse[E1, A1 >: A](that: => STM[E1, A1])(implicit ev: CanFail[E]): STM[E1, A1] =
    new STM(
      (journal, fiberId, stackSize) => {
        val reset = prepareResetJournal(journal)

        val continueM: TExit[E, A] => STM[E1, A1] = {
          case TExit.Fail(_)    => { reset(); that }
          case TExit.Succeed(a) => STM.succeed(a)
          case TExit.Retry      => { reset(); that }
        }

        val framesCount = stackSize.incrementAndGet()

        if (framesCount > STM.MaxFrames) {
          throw new STM.Resumable(self, Stack(continueM))
        } else {
          val continued =
            try {
              continueM(self.exec(journal, fiberId, stackSize))
            } catch {
              case res: STM.Resumable[e, e1, a, b] =>
                res.ks.push(continueM.asInstanceOf[TExit[e, a] => STM[e1, b]])
                throw res
            }

          continued.exec(journal, fiberId, stackSize)
        }
      }
    )

  /**
   * Returns a transactional effect that will produce the value of this effect in left side, unless it
   * fails, in which case, it will produce the value of the specified effect in right side.
   */
  def orElseEither[E1 >: E, B](that: => STM[E1, B])(implicit ev: CanFail[E]): STM[E1, Either[A, B]] =
    (self map (Left[A, B](_))) orElse (that map (Right[A, B](_)))

  /**
   * Maps the success value of this effect to unit.
   */
  def unit: STM[E, Unit] = as(())

  /**
   * Same as [[filter]]
   */
  def withFilter(f: A => Boolean): STM[E, A] = filter(f)

  /**
   * Named alias for `<*>`.
   */
  def zip[E1 >: E, B](that: => STM[E1, B]): STM[E1, (A, B)] =
    (self zipWith that)((a, b) => a -> b)

  /**
   * Named alias for `<*`.
   */
  def zipLeft[E1 >: E, B](that: => STM[E1, B]): STM[E1, A] =
    (self zip that) map (_._1)

  /**
   * Named alias for `*>`.
   */
  def zipRight[E1 >: E, B](that: => STM[E1, B]): STM[E1, B] =
    (self zip that) map (_._2)

  /**
   * Sequentially zips this value with the specified one, combining the values
   * using the specified combiner function.
   */
  def zipWith[E1 >: E, B, C](that: => STM[E1, B])(f: (A, B) => C): STM[E1, C] =
    self flatMap (a => that map (b => f(a, b)))

  private def continueWithM[E1, B](continueM: TExit[E, A] => STM[E1, B]): STM[E1, B] =
    new STM(
      (journal, fiberId, stackSize) => {
        val framesCount = stackSize.incrementAndGet()

        if (framesCount > STM.MaxFrames) {
          throw new STM.Resumable(self, Stack(continueM))
        } else {
          val continued =
            try {
              continueM(self.exec(journal, fiberId, stackSize))
            } catch {
              case res: STM.Resumable[e, e1, a, b] =>
                res.ks.push(continueM.asInstanceOf[TExit[e, a] => STM[e1, b]])
                throw res
            }

          continued.exec(journal, fiberId, stackSize)
        }
      }
    )

  private def run(journal: STM.internal.Journal, fiberId: Fiber.Id): TExit[E, A] = {
    type Cont = TExit[Any, Any] => STM[Any, Any]

    val stackSize = new AtomicLong()
    val stack     = new Stack[Cont]()
    var current   = self.asInstanceOf[STM[Any, Any]]
    var result    = null: AnyRef

    while (result eq null) {
      try {
        val v = current.exec(journal, fiberId, stackSize)

        if (stack.isEmpty)
          result = v
        else {
          val next = stack.pop()
          current = next(v)
        }
      } catch {
        case cont: STM.Resumable[_, _, _, _] =>
          current = cont.stm

          while (!cont.ks.isEmpty) stack.push(cont.ks.pop().asInstanceOf[Cont])

          stackSize.set(0)
      }
    }

    result.asInstanceOf[TExit[E, A]]
  }
}

object STM {

  private final class Resumable[E, E1, A, B](val stm: STM[E, A], val ks: Stack[internal.TExit[E, A] => STM[E1, B]])
      extends Throwable(null, null, false, false)

  private final val MaxFrames = 200

  private[stm] object internal {
    final val DefaultJournalSize = 4

    //Appears in reference comparisons, thus, cannot extends AnyVal to prevent boxing.
    final class Versioned[A](val value: A)

    type TxnId = Long

    type Journal =
      MutableMap[TRef[_], STM.internal.Entry]

    type Todo = () => Unit

    /**
     * Creates a function that can reset the journal.
     */
    def prepareResetJournal(journal: Journal): () => Unit = {
      val saved = new MutableMap[TRef[_], Entry](journal.size)

      val it = journal.entrySet.iterator
      while (it.hasNext) {
        val entry = it.next
        saved.put(entry.getKey, entry.getValue.copy())
      }

      () => { journal.clear(); journal.putAll(saved); () }
    }

    /**
     * Commits the journal.
     */
    def commitJournal(journal: Journal): Unit = {
      val it = journal.entrySet.iterator
      while (it.hasNext) it.next.getValue.commit()
    }

    /**
     * Allocates memory for the journal, if it is null, otherwise just clears it.
     */
    def allocJournal(journal: Journal): Journal =
      if (journal eq null) new MutableMap[TRef[_], Entry](DefaultJournalSize)
      else {
        journal.clear()
        journal
      }

    /**
     * Determines if the journal is valid.
     */
    def isValid(journal: Journal): Boolean = {
      var valid = true
      val it    = journal.entrySet.iterator
      while (valid && it.hasNext) valid = it.next.getValue.isValid
      valid
    }

    /**
     * Analyzes the journal, determining whether it is valid and whether it is
     * read only in a single pass. Note that information on whether the
     * journal is read only will only be accurate if the journal is valid, due
     * to short-circuiting that occurs on an invalid journal.
     */
    def analyzeJournal(journal: Journal): JournalAnalysis = {
      var result = JournalAnalysis.ReadOnly: JournalAnalysis
      val it     = journal.entrySet.iterator
      while ((result ne JournalAnalysis.Invalid) && it.hasNext) {
        val value = it.next.getValue
        if (value.isInvalid) result = JournalAnalysis.Invalid
        else if (value.isChanged) result = JournalAnalysis.ReadWrite
      }
      result
    }

    sealed trait JournalAnalysis extends Serializable with Product
    object JournalAnalysis {
      case object Invalid   extends JournalAnalysis
      case object ReadOnly  extends JournalAnalysis
      case object ReadWrite extends JournalAnalysis
    }

    /**
     * Determines if the journal is invalid.
     */
    def isInvalid(journal: Journal): Boolean = !isValid(journal)

    /**
     * Atomically collects and clears all the todos from any `TRef` that
     * participated in the transaction.
     */
    def collectTodos(journal: Journal): MutableMap[TxnId, Todo] = {
      import collection.JavaConverters._

      val allTodos  = new MutableMap[TxnId, Todo](DefaultJournalSize)
      val emptyTodo = Map.empty[TxnId, Todo]

      val it = journal.entrySet.iterator
      while (it.hasNext) {
        val tref = it.next.getValue.tref
        val todo = tref.todo

        var loop = true
        while (loop) {
          val oldTodo = todo.get

          loop = !todo.compareAndSet(oldTodo, emptyTodo)

          if (!loop) allTodos.putAll(oldTodo.asJava): @silent("JavaConverters")
        }
      }

      allTodos
    }

    /**
     * Executes the todos in the current thread, sequentially.
     */
    def execTodos(todos: MutableMap[TxnId, Todo]): Unit = {
      val it = todos.entrySet.iterator
      while (it.hasNext) it.next.getValue.apply()
    }

    /**
     * For the given transaction id, adds the specified todo effect to all
     * `TRef` values.
     */
    def addTodo(txnId: TxnId, journal: Journal, todoEffect: Todo): Boolean = {
      var added = false

      val it = journal.entrySet.iterator
      while (it.hasNext) {
        val tref = it.next.getValue.tref

        var loop = true
        while (loop) {
          val oldTodo = tref.todo.get

          if (!oldTodo.contains(txnId)) {
            val newTodo = oldTodo.updated(txnId, todoEffect)

            loop = !tref.todo.compareAndSet(oldTodo, newTodo)

            if (!loop) added = true
          } else loop = false
        }
      }

      added
    }

    /**
     * Runs all the todos.
     */
    def completeTodos[E, A](io: IO[E, A], journal: Journal, platform: Platform): TryCommit[E, A] = {
      val todos = collectTodos(journal)

      if (todos.size > 0) platform.executor.submitOrThrow(() => execTodos(todos))

      TryCommit.Done(io)
    }

    /**
     * Finds all the new todo targets that are not already tracked in the `oldJournal`.
     */
    def untrackedTodoTargets(oldJournal: Journal, newJournal: Journal): Journal = {
      val untracked = new MutableMap[TRef[_], Entry](newJournal.size)

      untracked.putAll(newJournal)

      val it = newJournal.entrySet.iterator
      while (it.hasNext) {
        val entry = it.next
        val key   = entry.getKey
        val value = entry.getValue
        if (oldJournal.containsKey(key)) {
          // We already tracked this one, remove it:
          untracked.remove(key)
        } else if (value.isNew) {
          // This `TRef` was created in the current transaction, so no need to
          // add any todos to it, because it cannot be modified from the outside
          // until the transaction succeeds; so any todo added to it would never
          // succeed.
          untracked.remove(key)
        }
      }

      untracked
    }

    def tryCommitAsync[E, A](
      journal: Journal,
      platform: Platform,
      fiberId: Fiber.Id,
      stm: STM[E, A],
      txnId: TxnId,
      done: AtomicBoolean
    )(
      k: IO[E, A] => Unit
    ): Unit = {
      def complete(io: IO[E, A]): Unit = { done.set(true); k(io) }

      @tailrec
      def suspend(accum: Journal, journal: Journal): Unit = {
        addTodo(txnId, journal, () => tryCommitAsync(null, platform, fiberId, stm, txnId, done)(k))

        if (isInvalid(journal)) tryCommit(platform, fiberId, stm) match {
          case TryCommit.Done(io) => complete(io)
          case TryCommit.Suspend(journal2) =>
            val untracked = untrackedTodoTargets(accum, journal2)

            if (untracked.size > 0) {
              accum.putAll(untracked)

              suspend(accum, untracked)
            }
        }
      }

      Sync(done) {
        if (!done.get) {
          if (journal ne null) suspend(journal, journal)
          else
            tryCommit(platform, fiberId, stm) match {
              case TryCommit.Done(io)         => complete(io)
              case TryCommit.Suspend(journal) => suspend(journal, journal)
            }
        }
      }
    }

    def tryCommit[E, A](platform: Platform, fiberId: Fiber.Id, stm: STM[E, A]): TryCommit[E, A] = {
      var journal = null.asInstanceOf[MutableMap[TRef[_], Entry]]
      var value   = null.asInstanceOf[TExit[E, A]]

      var loop = true

      while (loop) {
        journal = allocJournal(journal)
        value = stm.run(journal, fiberId)

        val analysis = analyzeJournal(journal)

        if (analysis ne JournalAnalysis.Invalid) {
          loop = false

          value match {
            case _: TExit.Succeed[_] =>
              if (analysis eq JournalAnalysis.ReadWrite) {
                Sync(globalLock) {
                  if (isValid(journal)) commitJournal(journal) else loop = true
                }
              } else {
                Sync(globalLock) {
                  if (isInvalid(journal)) loop = true
                }
              }

            case _ =>
          }
        }
      }

      value match {
        case TExit.Succeed(a) => completeTodos(IO.succeed(a), journal, platform)
        case TExit.Fail(e)    => completeTodos(IO.fail(e), journal, platform)
        case TExit.Retry      => TryCommit.Suspend(journal)
      }
    }

    def makeTxnId(): Long = txnCounter.incrementAndGet()

    private[this] val txnCounter: AtomicLong = new AtomicLong()

    final val globalLock = new AnyRef {}

    sealed trait TExit[+A, +B] extends Serializable with Product
    object TExit {
      final case class Fail[+A](value: A)    extends TExit[A, Nothing]
      final case class Succeed[+B](value: B) extends TExit[Nothing, B]
      case object Retry                      extends TExit[Nothing, Nothing]
    }

    abstract class Entry { self =>
      type A

      val tref: TRef[A]

      protected[this] val expected: Versioned[A]
      protected[this] var newValue: A

      val isNew: Boolean

      private[this] var _isChanged = false

      def unsafeSet(value: Any): Unit = {
        _isChanged = true
        newValue = value.asInstanceOf[A]
      }

      def unsafeGet[B]: B = newValue.asInstanceOf[B]

      /**
       * Commits the new value to the `TRef`.
       */
      def commit(): Unit = tref.versioned = new Versioned(newValue)

      /**
       * Creates a copy of the Entry.
       */
      def copy(): Entry = new Entry {
        type A = self.A
        val tref     = self.tref
        val expected = self.expected
        val isNew    = self.isNew
        var newValue = self.newValue
        _isChanged = self.isChanged
      }

      /**
       * Determines if the entry is invalid. This is the negated version of
       * `isValid`.
       */
      def isInvalid: Boolean = !isValid

      /**
       * Determines if the entry is valid. That is, if the version of the
       * `TRef` is equal to the expected version.
       */
      def isValid: Boolean = tref.versioned eq expected

      /**
       * Determines if the variable has been set in a transaction.
       */
      def isChanged: Boolean = _isChanged

      override def toString: String =
        s"Entry(expected.value = ${expected.value}, newValue = $newValue, tref = $tref, isChanged = $isChanged)"
    }

    object Entry {

      /**
       * Creates an entry for the journal, given the `TRef` being untracked, the
       * new value of the `TRef`, and the expected version of the `TRef`.
       */
      def apply[A0](tref0: TRef[A0], isNew0: Boolean): Entry = {
        val versioned = tref0.versioned

        new Entry {
          type A = A0
          val tref     = tref0
          val isNew    = isNew0
          val expected = versioned
          var newValue = versioned.value
        }
      }
    }

    sealed abstract class TryCommit[+E, +A]
    object TryCommit {
      final case class Done[+E, +A](io: IO[E, A]) extends TryCommit[E, A]
      final case class Suspend(journal: Journal)  extends TryCommit[Nothing, Nothing]
    }
  }

  import internal._

  /**
   * Atomically performs a batch of operations in a single transaction.
   */
  def atomically[E, A](stm: STM[E, A]): IO[E, A] =
    IO.effectSuspendTotalWith { (platform, fiberId) =>
      tryCommit(platform, fiberId, stm) match {
        case TryCommit.Done(io) => io // TODO: Interruptible in Suspend
        case TryCommit.Suspend(journal) =>
          val txnId     = makeTxnId()
          val done      = new AtomicBoolean(false)
          val interrupt = UIO(Sync(done) { done.set(true) })
          val async     = IO.effectAsync[E, A](tryCommitAsync(journal, platform, fiberId, stm, txnId, done))

          async ensuring interrupt
      }
    }

  /**
   * Checks the condition, and if it's true, returns unit, otherwise, retries.
   */
  def check(p: Boolean): STM[Nothing, Unit] = if (p) STM.unit else retry

  /**
   * Collects all the transactional effects in a list, returning a single
   * transactional effect that produces a list of values.
   */
  def collectAll[E, A](i: Iterable[STM[E, A]]): STM[E, List[A]] =
    i.foldRight[STM[E, List[A]]](STM.succeed(Nil)) {
      case (stm, acc) =>
        acc.zipWith(stm)((xs, x) => x :: xs)
    }

  /**
   * Kills the fiber running the effect.
   */
  def die(t: Throwable): STM[Nothing, Nothing] = succeed(throw t)

  /**
   * Kills the fiber running the effect with a `RuntimeException` that contains
   * the specified message.
   */
  def dieMessage(m: String): STM[Nothing, Nothing] = die(new RuntimeException(m))

  /**
   * Returns a value modelled on provided exit status.
   */
  def done[E, A](exit: TExit[E, A]): STM[E, A] =
    exit match {
      case TExit.Retry      => STM.retry
      case TExit.Fail(e)    => STM.fail(e)
      case TExit.Succeed(a) => STM.succeed(a)
    }

  /**
   * Returns a value that models failure in the transaction.
   */
  def fail[E](e: E): STM[E, Nothing] = new STM((_, _, _) => TExit.Fail(e))

  /**
   * Returns the fiber id of the fiber committing the transaction.
   */
  val fiberId: STM[Nothing, Fiber.Id] = new STM((_, fiberId, _) => TExit.Succeed(fiberId))

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and
   * returns a transactional effect that produces a new `List[B]`.
   */
  def foreach[E, A, B](as: Iterable[A])(f: A => STM[E, B]): STM[E, List[B]] =
    collectAll(as.map(f))

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and
   * returns a transactional effect that produces `Unit`.
   *
   * Equivalent to `foreach(as)(f).unit`, but without the cost of building
   * the list of results.
   */
  def foreach_[E, A, B](as: Iterable[A])(f: A => STM[E, B]): STM[E, Unit] =
    STM.succeed(as.iterator).flatMap { it =>
      def loop: STM[E, Unit] =
        if (it.hasNext) f(it.next) *> loop
        else STM.unit

      loop
    }

  /**
   * Creates an STM effect from an `Either` value.
   */
  def fromEither[E, A](e: => Either[E, A]): STM[E, A] =
    STM.suspend {
      e match {
        case Left(t)  => STM.fail(t)
        case Right(a) => STM.succeed(a)
      }
    }

  /**
   * Creates an STM effect from a `Try` value.
   */
  def fromTry[A](a: => Try[A]): STM[Throwable, A] =
    STM.suspend {
      Try(a).flatten match {
        case Failure(t) => STM.fail(t)
        case Success(a) => STM.succeed(a)
      }
    }

  /**
   * Creates an `STM` value from a partial (but pure) function.
   */
  def partial[A](a: => A): STM[Throwable, A] = fromTry(Try(a))

  /**
   * Abort and retry the whole transaction when any of the underlying
   * transactional variables have changed.
   */
  val retry: STM[Nothing, Nothing] = new STM((_, _, _) => TExit.Retry)

  /**
   * Returns an `STM` effect that succeeds with the specified value.
   */
  def succeed[A](a: A): STM[Nothing, A] = new STM((_, _, _) => TExit.Succeed(a))

  /**
   * Suspends creation of the specified transaction lazily.
   */
  def suspend[E, A](stm: => STM[E, A]): STM[E, A] =
    STM.succeed(stm).flatten

  /**
   * Returns an `STM` effect that succeeds with `Unit`.
   */
  val unit: STM[Nothing, Unit] = succeed(())
}
