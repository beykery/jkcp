package test;

import java.util.ArrayList;
import java.util.LinkedList;

/**
 * @Auther: zhuxiaoqing
 * @Date: 2020/4/27 11:27
 * @Description:
 */
public class Test {

    public static void main(String[] args) {
        printTime(100, 50, 5);
        printTime(100, 10000, 4);
    }


    /*
     * 比较数组和链表执行插入数据时花费的时间
     * len    定义数组的长度
     * num    循环插入的次数(插入一次时间太短难比较)
     * index  每次插入的位置
     */
    public static void printTime(int len, int num, int index) {
        LinkedList<Integer> link = new LinkedList<Integer>();//定义链表
        ArrayList<Integer> arr = new ArrayList<Integer>();//定义数组
        //ArrayDeque<Integer> arr=new ArrayDeque<Integer>();//定义数组
        //为数组赋初值
        for (int i = 0; i < len; i++) {
            arr.add(i);
            link.add(i);
        }
        //计算数组执行操作花费的时间
        long startTime = System.nanoTime();
        for (int i = 0; i < num; i++) {
            arr.add(index, 2);
        }
        long endTime = System.nanoTime();
        System.out.println("数组花费时间：" + (endTime - startTime) + "纳秒");
        //计算链表执行相同操作花费的时间
        long sTime = System.nanoTime();
        for (int i = 0; i < num; i++) {
            link.add(index, 2);
        }
        long eTime = System.nanoTime();
        System.out.println("链表花费时间：" + (eTime - sTime) + "纳秒");
    }
}
