package tttomat19;

import io.reactivex.Emitter;
import io.reactivex.Flowable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.Random;

public class TestReactivePullBackpressure {

    public static void main(String[] args) throws InterruptedException {

        Flowable<Integer> flowable = Flowable.generate(new Consumer<Emitter<Integer>>() {
            @Override
            public void accept(Emitter<Integer> emitter) throws Exception {
                System.out.println("emit");
                emitter.onNext(new Random().nextInt());
            }
        }).filter(integer -> {
            System.out.println("predicate " + integer);
            return true;
        }).doOnNext(integer -> {
            System.out.println("doOnNext");
        });

        Subscriber<Integer> subscriber = new Subscriber<Integer>() {

            Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                System.out.println("request");
                subscription.request(1);
            }

            @Override
            public void onNext(Integer o) {
                System.out.println("next: " + o);
                subscription.request(1);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        };

        flowable.subscribeOn(Schedulers.newThread()).subscribe(subscriber);

        Thread.sleep(5000);

    }

}
