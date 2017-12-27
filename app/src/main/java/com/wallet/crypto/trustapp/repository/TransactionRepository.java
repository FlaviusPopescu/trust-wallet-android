package com.wallet.crypto.trustapp.repository;

import com.wallet.crypto.trustapp.entity.NetworkInfo;
import com.wallet.crypto.trustapp.entity.Wallet;
import com.wallet.crypto.trustapp.entity.ServiceException;
import com.wallet.crypto.trustapp.entity.Transaction;
import com.wallet.crypto.trustapp.service.AccountKeystoreService;
import com.wallet.crypto.trustapp.service.BlockExplorerClientType;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jFactory;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.reactivex.observers.DisposableSingleObserver;

public class TransactionRepository implements TransactionRepositoryType {

	private final EthereumNetworkRepositoryType networkRepository;
	private final AccountKeystoreService accountKeystoreService;
	private final TransactionLocalSource transactionLocalSource;
	private final BlockExplorerClientType blockExplorerClient;

	public TransactionRepository(
			EthereumNetworkRepositoryType networkRepository,
			AccountKeystoreService accountKeystoreService,
			TransactionLocalSource inMemoryCache,
			TransactionLocalSource inDiskCache,
			BlockExplorerClientType blockExplorerClient) {
		this.networkRepository = networkRepository;
		this.accountKeystoreService = accountKeystoreService;
		this.blockExplorerClient = blockExplorerClient;
		this.transactionLocalSource = inMemoryCache;

		this.networkRepository.addOnChangeDefaultNetwork(this::onNetworkChanged);
	}

    @Override
	public Single<Transaction[]> fetchTransaction(Wallet wallet) {
		return transactionLocalSource.fetchTransaction(wallet)
				.onErrorResumeNext(Single
						.fromObservable(blockExplorerClient.fetchTransactions(wallet.address))
						.lift(observer -> new DisposableSingleObserver<Transaction[]>() {
							@Override
							public void onSuccess(Transaction[] transactions) {
								transactionLocalSource.putTransactions(wallet, transactions);
								observer.onSuccess(transactions);
							}

							@Override
							public void onError(Throwable e) {
								observer.onError(e);
							}
						}));

	}

	@Override
	public Maybe<Transaction> findTransaction(Wallet wallet, String transactionHash) {
		return fetchTransaction(wallet)
				.flatMapMaybe(transactions -> {
					for (Transaction transaction : transactions) {
						if (transaction.hash.equals(transactionHash)) {
							return Maybe.just(transaction);
						}
					}
					return null;
				});
	}

	@Override
	public Completable createTransaction(Wallet from, String toAddress, String wei, String password) {
		final Web3j web3j = Web3jFactory
                .build(new HttpService(networkRepository.getDefaultNetwork().rpcServerUrl));

		return Single.fromCallable(() -> {
			EthGetTransactionCount ethGetTransactionCount = web3j
					.ethGetTransactionCount(from.address, DefaultBlockParameterName.LATEST)
					.send();
			return ethGetTransactionCount.getTransactionCount();
		})
		.flatMap(nonce -> accountKeystoreService.signTransaction(
		        from, password, toAddress, wei, nonce.longValue(),
                networkRepository.getDefaultNetwork().chainId))
		.flatMapCompletable(signedMessage -> Completable.fromAction(() -> {
			EthSendTransaction raw = web3j
					.ethSendRawTransaction(Numeric.toHexString(signedMessage))
					.send();
			if (raw.hasError()) {
				throw new ServiceException(raw.getError().getMessage());
			}
		}));
	}

    private void onNetworkChanged(NetworkInfo networkInfo) {
        transactionLocalSource.clear();
    }
}