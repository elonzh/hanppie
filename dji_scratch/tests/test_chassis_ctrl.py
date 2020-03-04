import event_client
import rm_ctrl
import tools
import rm_define
import time
import sys
import re

event = event_client.EventClient()
chassis_ctrl = rm_ctrl.ChassisCtrl(event)
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
chassis_ctrl.set_mode(rm_define.chassis_sdk_free_mode)
tank_ctrl = rm_ctrl.TankCtrl(chassis_ctrl, gimbal_ctrl)

tools.wait(50)

def test_set_move_speed():
    print('set_move_speed()')
    chassis_ctrl.set_move_speed(2)
    chassis_ctrl.move_with_time(rm_define.chassis_front, 2)
    chassis_ctrl.set_move_speed(3)
    chassis_ctrl.move_with_time(rm_define.chassis_front, 2)
    chassis_ctrl.set_move_speed(1)
    chassis_ctrl.move_with_time(rm_define.chassis_front, 2)

def test_set_wheel_speed():
    print('set_wheel_speed()')
    chassis_ctrl.set_wheel_speed(100, 100, 100, 100)
    time.sleep(3)
    chassis_ctrl.set_wheel_speed(-100, -100, -100, -100)
    time.sleep(3)
    chassis_ctrl.stop()

def test_set_yaw_speed():
    print('set_yaw_speed()')
    chassis_ctrl.set_yaw_speed(30)
    chassis_ctrl.rotate_with_time(rm_define.clockwise, 4)
    chassis_ctrl.set_yaw_speed(60)
    chassis_ctrl.rotate_with_time(rm_define.anticlockwise, 2)

def test_set_gimbal_offset():
    tank_ctrl.set_work_mode(rm_define.tank_mode_free)
    chassis_ctrl.set_follow_gimbal_offset(0)
    time.sleep(3)
    chassis_ctrl.set_follow_gimbal_offset(-30)
    time.sleep(3)
    chassis_ctrl.set_follow_gimbal_offset(45)
    chassis_ctrl.stop()

def test_move():
    print('move()')
    chassis_ctrl.move(rm_define.chassis_front)
    time.sleep(2)
    chassis_ctrl.move(rm_define.chassis_back)
    time.sleep(2)
    chassis_ctrl.move(rm_define.chassis_left_back)
    time.sleep(2)
    chassis_ctrl.move(rm_define.chassis_right_front)
    time.sleep(2)
    chassis_ctrl.move(rm_define.chassis_left_front)
    time.sleep(2)
    chassis_ctrl.move(rm_define.chassis_right_back)
    time.sleep(2)
    chassis_ctrl.stop()

def test_move_and_rotate():
    print('move_and_rotate()')
    chassis_ctrl.set_move_speed(0.5)
    chassis_ctrl.move_and_rotate(30, rm_define.clockwise)
    time.sleep(3)
    chassis_ctrl.move_and_rotate(30, rm_define.anticlockwise)
    time.sleep(3)
    chassis_ctrl.stop()

def test_move_with_distance():
    print('move_with_distance()')
    chassis_ctrl.move_with_distance(rm_define.chassis_front, 0.5)
    chassis_ctrl.move_with_distance(rm_define.chassis_back, 1)
    chassis_ctrl.move_with_distance(rm_define.chassis_left_back, 1.5)
    chassis_ctrl.move_with_distance(rm_define.chassis_right_front, 2.0)
    chassis_ctrl.move_with_distance(rm_define.chassis_left_front, 2.5)
    chassis_ctrl.move_with_distance(rm_define.chassis_right_back, 3.0)

def test_move_with_time():
    print('move_with_time()')
    chassis_ctrl.move_with_time(rm_define.chassis_front, 2)
    chassis_ctrl.move_with_time(rm_define.chassis_back, 2)
    chassis_ctrl.move_with_time(rm_define.chassis_left_back, 3)
    chassis_ctrl.move_with_time(rm_define.chassis_right_front, 3)
    chassis_ctrl.move_with_time(rm_define.chassis_left_front, 1)
    chassis_ctrl.move_with_time(rm_define.chassis_right_back, 1)

def test_move_with_degree():
    print('move_with_degree')
    chassis_ctrl.move_with_degree(45, 3)
    chassis_ctrl.move_with_degree(-45, 3)
    chassis_ctrl.move_with_degree(135, 3)
    chassis_ctrl.move_with_degree(-135, 3)

def test_rotate():
    print('rotate()')
    chassis_ctrl.rotate(rm_define.clockwise)
    time.sleep(3)
    chassis_ctrl.rotate(rm_define.anticlockwise)
    time.sleep(3)
    chassis_ctrl.stop()

def test_rotate_with_time():
    print('rotate_with_time()')
    chassis_ctrl.rotate_with_time(rm_define.anticlockwise, 3)
    chassis_ctrl.rotate_with_time(rm_define.clockwise, 3)

def test_rotate_with_degree():
    print('rotate_with_degree()')
    chassis_ctrl.rotate_with_degree(rm_define.anticlockwise, 30)
    chassis_ctrl.rotate_with_degree(rm_define.clockwise, 30)

def test_stop():
    print('stop()')
    chassis_ctrl.move(rm_define.chassis_front)
    time.sleep(2)
    chassis_ctrl.stop()
    chassis_ctrl.set_mode(rm_define.follow_mode)
    gimbal_ctrl.rotate(rm_define.gimbal_left)
    time.sleep(2)
    gimbal_ctrl.stop()
    chassis_ctrl.stop()
    chassis_ctrl.set_mode(rm_define.chassis_sdk_free_mode)

def stop():
    print('stop cur module')
    chassis_ctrl.stop()
    gimbal_ctrl.stop()

def del_obj():
    global chassis_ctrl
    global gimbal_ctrl
    global tank_ctrl
    del chassis_ctrl
    del gimbal_ctrl
    del tank_ctrl


help_info = '''
It's test module script

Usage:
        python /data/python_files/bin/test_[module]_ctrl.py [options] [target_functions] [test_times]

Module:
        chassis, gimbal, led, gun, vision, armor...

Options:
        -h, -H
                help info

        -l, -L
                list the currently supported test function

        -e, -E <target_functions..> <test_times>
                run target test functions, make sure the function has been added in the file

        -a, -A <test_times>
                run all test function about the module

        -s, -S
                stop cur module action

Other:
        If there are no options,
                the test script will run all test script about this module after 5s.

        Not Support more options than one
                example: python /data/python_files/bin/test_chassis_ctrl.py -h -l -t

Example:
        python /CODE_PATH/test_chassis_ctrl.py -e move 2
        python /CODE_PATH/test_chassis_ctrl.py -e move 3 move_with_distance 2
        python /CODE_PATH/test_chassis_ctrl.py -a 2
        python /CODE_PATH/test_chassis_ctrl.py -a
        python /CODE_PATH/test_chassis_ctrl.py
'''

no_options_info = '''
*************************************************************
*       After 5s, run all test function.                    *
*       You can use -h option to see more infomation.       *
*************************************************************
'''

def start():
    argv = sys.argv[1:len(sys.argv)+1]
    _globals = globals()
    func_dict = {}
    func_list = []
    for name in _globals.keys():
        if 'test_' in name:
            func_dict[name] = _globals[name]
            func_list.append(name)

    option = None

    if len(argv) == 0:
        print(no_options_info)
        tools.wait(5000)
        option = '-a'
    else:
        option = argv.pop(0)

    option = option.lower()

    if '-h' == option:
        print(help_info)
    elif '-l' == option:
        print('Please input the index to test the function, to ues space break them if you input more than one, input q or Q to exit:')
        for i in range(len(func_list)):
            out_str = str(i) + ' ' + func_list[i] + '()'
            print(out_str)

        user_input = input()
        user_input = re.split(" +", user_input)

        if 'q' in user_input or 'Q' in user_input or len(user_input) == 1 and user_input[0] == '':
            print('test exit')
            return

        for i in user_input:
            try:
                if i.isdigit() == True:
                    index = int(i)
                    if index < len(func_list):
                        func_dict[func_list[index]]()
                    else:
                        print('user input error, ignore')
                else:
                    print('user input error, ignore')
            except Exception as e:
                print('Other error, please check test script', e.message)
    elif '-e' == option:
        f_dict = {}
        last_f = None
        for param in argv:
            if param.isdigit() == True:
                if last_f != None:
                    f_dict[last_f] = int(param)
                    last_f = None
            else:
                if last_f != None:
                    f_dict[last_f] = 1
                last_f = param
        if last_f != None and last_f not in f_dict.keys():
            f_dict[last_f] = 1
        for f_name, f_times in f_dict.items():
            if f_name.find('test') == -1:
                f_name = 'test_' + f_name
            if f_name in func_dict.keys():
                info = 'Will run %d '%f_times + 'times'
                print(info)
                for i in range(f_times):
                    info ='running %d'%(i+1) + '.th'
                    print(info)
                    func_dict[f_name]()
            else:
                info = 'function %s'%f_name + ' is not defined'
                print(info)
    elif '-a' == option:
        f_times = 1
        if len(argv) != 0 and argv[0].isdigit():
            f_times = int(argv[0])
        info = 'Will run %d '%f_times + 'times'
        print(info)
        for i in range(f_times):
            info ='running %d'%(i+1) + '.th'
            print(info)
            for name, f in func_dict.items():
                f()
    elif '-s' == option:
        stop()
    else:
        print('Invalid options, to use -h option to see more infomation')

try:
    start()
except Exception as e:
    print(e.message)
    pass

stop()

del_obj()

tools.wait(500)

event.stop()
