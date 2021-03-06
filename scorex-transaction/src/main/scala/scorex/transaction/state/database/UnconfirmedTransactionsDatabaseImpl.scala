package scorex.transaction.state.database

import scorex.transaction.{Transaction, UnconfirmedTransactionsStorage}
import scorex.utils.ScorexLogging

import scala.collection.concurrent.TrieMap
import scala.collection.mutable


class UnconfirmedTransactionsDatabaseImpl(val sizeLimit: Int = 1000) extends UnconfirmedTransactionsStorage with ScorexLogging {

  private type TxKey = mutable.WrappedArray.ofByte

  private val transactions = TrieMap[TxKey, Transaction]()

  private def key(signature: Array[Byte]): TxKey = new TxKey(signature)

  private def key(tx: Transaction): TxKey = key(tx.signature)

  override def putIfNew(tx: Transaction, txValidator: Transaction => Boolean): Boolean =
    if (transactions.size < sizeLimit) {
      val txKey = key(tx)
      if (transactions.contains(txKey)) {
        false
      } else if (txValidator(tx)) {
        transactions.update(txKey, tx)
        true
      } else {
        log.error(s"Transaction $tx is not valid")
        false
      }
    } else {
      log.warn("Transaction pool size limit is reached")
      false
    }

  override def remove(tx: Transaction): Unit = transactions -= key(tx)

  override def all(): Seq[Transaction] = transactions.values.toSeq

  override def getBySignature(signature: Array[Byte]): Option[Transaction] = transactions.get(key(signature))
}
